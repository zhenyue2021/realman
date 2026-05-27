package org.jeecg.modules.device.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.WorkOrderConstant;
import org.jeecg.modules.device.dto.MasterLoginDTO;
import org.jeecg.modules.device.dto.WorkOrderDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.entity.IotMasterLoginLog;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotMasterLoginLogMapper;
import org.jeecg.modules.device.service.IMasterLoginResolveService;
import org.jeecg.modules.device.service.impl.master.MasterAuthValidateService;
import org.jeecg.modules.device.service.impl.master.MasterWorkOrderResolveService;
import org.jeecg.modules.device.service.impl.master.TeleopRelationCacheService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.vo.MasterLoginResolveVO;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主控登录门面：协调授权校验、工单解析、登录记录、遥操缓存、WebSocket 推送。
 * 事务边界在此统一声明，子域服务不重复标注 {@code @Transactional}。
 *
 * @see MasterAuthValidateService   控制器校验与授权查询
 * @see MasterWorkOrderResolveService 工单解析与 DTO 组装
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class MasterLoginResolveServiceImpl extends ServiceImpl<IotMasterLoginLogMapper, IotMasterLoginLog>
        implements IMasterLoginResolveService {

    private final IotDeviceMapper deviceMapper;
    private final IWorkOrderService workOrderService;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final ObjectMapper objectMapper;
    private final MasterAuthValidateService authValidateService;
    private final MasterWorkOrderResolveService workOrderResolveService;
    private final TeleopRelationCacheService teleopRelationCacheService;

    /** dev 环境缺省主控编码（仅 allow-default-device-code=true 时生效） */
    @Value("${device.master.device_code:master_develop001_30-50-f1-01-cc-5f}")
    private String defaultMasterCode;

    @Value("${device.master.allow-default-device-code:false}")
    private boolean allowDefaultDeviceCode;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotMasterLoginLog recordLogin(MasterLoginDTO dto) {
        if ((dto.getDeviceId() == null || dto.getDeviceId().isEmpty())
                && (dto.getDeviceCode() == null || dto.getDeviceCode().isEmpty())) {
            throw new IllegalArgumentException("主控设备ID或设备编码不能为空");
        }

        IotDevice controller = null;
        if (dto.getDeviceId() != null && !dto.getDeviceId().isEmpty()) {
            controller = deviceMapper.selectById(dto.getDeviceId());
        }
        if (controller == null && dto.getDeviceCode() != null && !dto.getDeviceCode().isEmpty()) {
            controller = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getDeviceCode, dto.getDeviceCode()));
        }
        if (controller == null) {
            throw new IllegalArgumentException("主控设备不存在");
        }
        if (controller.getDeviceType() == null || controller.getDeviceType() != 2) {
            throw new IllegalArgumentException("该设备不是主控设备，无法记录主控登录");
        }

        LocalDateTime now = LocalDateTime.now();
        IotMasterLoginLog loginLog = new IotMasterLoginLog();
        loginLog.setControllerId(controller.getId());
        loginLog.setControllerCode(controller.getDeviceCode());
        loginLog.setOperatorId(dto.getOperatorId());
        loginLog.setOperatorName(dto.getOperatorName());
        loginLog.setAssociatedRobotId(dto.getAssociatedRobotId());
        loginLog.setAssociatedRobotCode(dto.getAssociatedRobotCode());
        loginLog.setLoginTime(now);
        loginLog.setCreateTime(now);
        save(loginLog);

        controller.setLastLoginTime(now);
        deviceMapper.updateById(controller);
        return loginLog;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MasterLoginResolveVO resolve(HttpServletRequest request, MasterLoginDTO dto) {
        if (dto == null) {
            throw new RuntimeException("请求体不能为空");
        }

        authValidateService.bindOperatorFromLogin(request, dto);
        resolveDeviceCode(dto);
        log.info("主控登录解析请求: operatorId={} deviceCode={}", dto.getOperatorId(), dto.getDeviceCode());

        IotDevice controller = authValidateService.lookupController(dto.getDeviceCode());
        authValidateService.checkControllerOnline(controller);
        LocalDateTime now = LocalDateTime.now();
        List<String> departIds = authValidateService.lookupDepartIds(dto.getOperatorId());
        List<IotDeviceAuth> auths = authValidateService.queryAuths(controller, departIds, now);

        MasterLoginResolveVO vo = new MasterLoginResolveVO();
        vo.setController(controller);
        List<UsageStatusVO.RobotBasicVO> availableRobots = authValidateService.buildAvailableRobots(auths);
        if (CollectionUtil.isEmpty(availableRobots)) {
            log.warn("主控登录无可用机器人: operatorId={} deviceCode={}", dto.getOperatorId(), dto.getDeviceCode());
            return vo;
        }
        vo.setAvailableRobots(availableRobots);

        List<String> robotCodes = availableRobots.stream()
                .map(UsageStatusVO.RobotBasicVO::getRobotCode)
                .toList();
        List<WorkOrder> pendingOrders = workOrderService.listPendingForRobotCodes(robotCodes);
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            log.info("主控登录无待处理工单: operatorId={} deviceCode={}", dto.getOperatorId(), dto.getDeviceCode());
            IotMasterLoginLog loginLog = recordLogin(buildLoginLogDto(controller, dto));
            vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);
            return vo;
        }
        vo.setPendingWorkOrders(pendingOrders);

        WorkOrder firstOrder = pendingOrders.getFirst();
        IotDevice robot = workOrderResolveService.resolveRobotByWorkOrder(firstOrder.getId());
        authValidateService.assertRobotAuthorized(robot, availableRobots);

        MasterLoginDTO logDto = buildLoginLogDto(controller, dto);
        if (robot != null) {
            logDto.setAssociatedRobotId(robot.getId());
            logDto.setAssociatedRobotCode(robot.getDeviceCode());
        }

        IotMasterLoginLog loginLog = recordLogin(logDto);
        vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);

        registerAfterCommitSideEffects(controller, firstOrder, robot);

        WorkOrderDTO workOrderDTO = workOrderResolveService.buildWorkOrderDto(firstOrder);
        vo.setPendingWorkOrder(workOrderDTO);
        if (robot != null) {
            vo.setCurrentRobot(authValidateService.toRobotBasicVO(robot));
        }

        log.info("主控登录解析成功: operatorId={} deviceCode={} workOrderId={} robotCode={}",
                dto.getOperatorId(), dto.getDeviceCode(), firstOrder.getId(),
                robot != null ? robot.getDeviceCode() : "null");
        return vo;
    }

    private void resolveDeviceCode(MasterLoginDTO dto) {
        if (dto.getDeviceCode() != null && !dto.getDeviceCode().isBlank()) {
            return;
        }
        if (allowDefaultDeviceCode && defaultMasterCode != null && !defaultMasterCode.isBlank()) {
            dto.setDeviceCode(defaultMasterCode);
            log.warn("[ControllerLogin] 未传 deviceCode，使用配置缺省值（allow-default-device-code=true）");
            return;
        }
        throw new RuntimeException("deviceCode 不能为空");
    }

    private void registerAfterCommitSideEffects(IotDevice controller, WorkOrder firstOrder, IotDevice robot) {
        final String masterDeviceCode = controller.getDeviceCode();
        final String robotDeviceCode = robot != null ? robot.getDeviceCode() : null;
        final String orderStatus = firstOrder.getStatus();
        final String workOrderJson;
        final String robotJson;
        try {
            workOrderJson = objectMapper.writeValueAsString(firstOrder);
            robotJson = robot != null ? objectMapper.writeValueAsString(robot) : null;
        } catch (Exception e) {
            throw new RuntimeException("序列化工单/机器人信息失败", e);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (robotDeviceCode != null) {
                    teleopRelationCacheService.bind(masterDeviceCode, robotDeviceCode);
                }
                pushWorkOrderViaWebSocket(masterDeviceCode, orderStatus, workOrderJson, robotJson);
            }
        });
    }

    private MasterLoginDTO buildLoginLogDto(IotDevice controller, MasterLoginDTO dto) {
        MasterLoginDTO logDto = new MasterLoginDTO();
        logDto.setDeviceId(controller.getId());
        logDto.setDeviceCode(controller.getDeviceCode());
        logDto.setOperatorId(dto.getOperatorId());
        logDto.setOperatorName(dto.getOperatorName());
        return logDto;
    }

    private void pushWorkOrderViaWebSocket(
            String masterDeviceCode, String orderStatus, String workOrderJson, String robotJson) {
        try {
            if (WorkOrderConstant.ORDER_STATUS.PENDING.equals(orderStatus)) {
                deviceWebSocketServer.pushPendingWorkOrder(masterDeviceCode, workOrderJson);
            } else if (WorkOrderConstant.ORDER_STATUS.STARTED.equals(orderStatus)) {
                deviceWebSocketServer.pushStartedWorkOrder(masterDeviceCode, workOrderJson);
            }
            if (robotJson != null) {
                deviceWebSocketServer.pushAssociatedDeviceInfo(masterDeviceCode, robotJson);
            }
        } catch (Exception e) {
            log.warn("[ControllerLogin] WebSocket 推送工单失败: master={} err={}",
                    masterDeviceCode, e.getMessage());
        }
    }
}
