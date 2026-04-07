package org.jeecg.modules.device.service.impl;

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
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.vo.MasterLoginResolveVO;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final StringRedisTemplate redisTemplate;
    private final MasterAuthValidateService authValidateService;
    private final MasterWorkOrderResolveService workOrderResolveService;

    /** 配置中心配置的 master 设备编码（未传时使用此默认值） */
    @Value("${device.master.device_code:master_develop001_30-50-f1-01-cc-5f}")
    private String masterCode;

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
        if (dto.getDeviceCode() == null) {
            dto.setDeviceCode(masterCode);
        }
        log.info("主控登录解析请求: {} deviceCode={}", dto, dto.getDeviceCode());

        // 1. 校验控制器与操作员授权
        IotDevice controller = authValidateService.lookupController(dto.getDeviceCode());
        authValidateService.checkControllerOnline(controller);
        LocalDateTime now = LocalDateTime.now();
        List<String> departIds = authValidateService.lookupDepartIds(dto.getOperatorId());
        List<IotDeviceAuth> auths = authValidateService.queryAuths(controller, departIds, now);

        MasterLoginResolveVO vo = new MasterLoginResolveVO();
        vo.setController(controller);
        vo.setAvailableRobots(authValidateService.buildAvailableRobots(auths));

        // 2. 查询当前主控及部门关联的待开始/进行中工单
        List<WorkOrder> pendingOrders = workOrderService
                .listPendingForControllerAndDepartments(controller.getDeviceCode(), departIds);
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            // 无工单：仅写登录日志，不推送工单/机器人信息
            IotMasterLoginLog loginLog = recordLogin(buildLoginLogDto(controller, dto));
            vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);
            return vo;
        }
        vo.setPendingWorkOrders(pendingOrders);

        // 3. 解析第一个工单绑定的机器人
        WorkOrder firstOrder = pendingOrders.get(0);
        IotDevice robot = workOrderResolveService.resolveRobotByWorkOrder(firstOrder.getId());

        MasterLoginDTO logDto = buildLoginLogDto(controller, dto);
        if (robot != null) {
            logDto.setAssociatedRobotId(robot.getId());
            logDto.setAssociatedRobotCode(robot.getDeviceCode());
        }

        // 4. 写登录日志
        IotMasterLoginLog loginLog = recordLogin(logDto);
        vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);

        // 5. 写入遥操关系缓存
        if (robot != null) {
            String masterDeviceCode = controller.getDeviceCode();
            String robotDeviceCode = robot.getDeviceCode();
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + masterDeviceCode, robotDeviceCode);
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotDeviceCode, masterDeviceCode);
            log.info("[TeleopCache] 写入遥操关系缓存: master={} robot={}", masterDeviceCode, robotDeviceCode);
        }

        // 6. WebSocket 推送工单与关联设备信息
        pushWorkOrderViaWebSocket(controller, firstOrder, robot);

        // 7. 组装返回 VO
        WorkOrderDTO workOrderDTO = workOrderResolveService.buildWorkOrderDto(firstOrder);
        vo.setPendingWorkOrder(workOrderDTO);
        if (robot != null) {
            vo.setCurrentRobot(authValidateService.toRobotBasicVO(robot));
        }
        return vo;
    }

    // ─── 私有辅助方法 ────────────────────────────────────────────────────────────

    /** 构建登录日志 DTO（公共基础部分，机器人信息由调用方按需补全）。 */
    private MasterLoginDTO buildLoginLogDto(IotDevice controller, MasterLoginDTO dto) {
        MasterLoginDTO logDto = new MasterLoginDTO();
        logDto.setDeviceId(controller.getId());
        logDto.setDeviceCode(controller.getDeviceCode());
        logDto.setOperatorId(dto.getOperatorId());
        logDto.setOperatorName(dto.getOperatorName());
        return logDto;
    }

    /** 通过 WebSocket 向前端推送工单状态及关联机器人信息。 */
    private void pushWorkOrderViaWebSocket(IotDevice controller, WorkOrder firstOrder, IotDevice robot) {
        try {
            String workOrderJson = objectMapper.writeValueAsString(firstOrder);
            if (WorkOrderConstant.ORDER_STATUS.PENDING.equals(firstOrder.getStatus())) {
                deviceWebSocketServer.pushPendingWorkOrder(controller.getDeviceCode(), workOrderJson);
            } else if (WorkOrderConstant.ORDER_STATUS.STARTED.equals(firstOrder.getStatus())) {
                deviceWebSocketServer.pushStartedWorkOrder(controller.getDeviceCode(), workOrderJson);
            }
            deviceWebSocketServer.pushAssociatedDeviceInfo(
                    controller.getDeviceCode(), objectMapper.writeValueAsString(robot));
        } catch (Exception e) {
            log.warn("[ControllerLogin] WebSocket 推送工单失败: workOrderId={}, err={}",
                    firstOrder.getId(), e.getMessage());
        }
    }
}
