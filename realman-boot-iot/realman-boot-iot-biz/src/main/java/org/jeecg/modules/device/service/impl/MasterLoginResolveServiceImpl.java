package org.jeecg.modules.device.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.MasterLoginDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.entity.IotMasterLoginLog;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotMasterLoginLogMapper;
import org.jeecg.modules.device.mapper.SysUserDepartLiteMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IMasterLoginResolveService;
import org.jeecg.modules.device.service.MasterAssociatedDevicePendingService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.util.MacResolveUtil;
import org.jeecg.modules.device.vo.MasterLoginResolveVO;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 主控端登录记录：写入登录日志并更新主控设备 last_login_time
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MasterLoginResolveServiceImpl extends ServiceImpl<IotMasterLoginLogMapper, IotMasterLoginLog>
        implements IMasterLoginResolveService {
    private static final int DEVICE_TYPE_CONTROLLER = 2;
    private static final int DEVICE_TYPE_ROBOT = 1;

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final SysUserDepartLiteMapper sysUserDepartLiteMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final IWorkOrderService workOrderService;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final MasterAssociatedDevicePendingService masterAssociatedDevicePendingService;
    /** 配置中心配置的master的Mac地址 */
    @Value("${device.master.mac:30:50:f1:01:cc:5f}")
    private String masterMac;
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
        IotMasterLoginLog log = new IotMasterLoginLog();
        log.setControllerId(controller.getId());
        log.setControllerCode(controller.getDeviceCode());
        log.setOperatorId(dto.getOperatorId());
        log.setOperatorName(dto.getOperatorName());
        log.setAssociatedRobotId(dto.getAssociatedRobotId());
        log.setAssociatedRobotCode(dto.getAssociatedRobotCode());
        log.setLoginTime(now);
        log.setCreateTime(now);
        save(log);

        controller.setLastLoginTime(now);
        deviceMapper.updateById(controller);
        return log;
    }


    @Transactional(rollbackFor = Exception.class)
    @Override
    public MasterLoginResolveVO resolve(HttpServletRequest request, MasterLoginDTO dto) {
        if (dto == null) {
            throw new RuntimeException("请求体不能为空");
        }

        /*String tenantId = request.getHeader("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }*/

        // 解析客户端 MAC：优先用客户端传来的 macAddress，传不了时走 ARP 兜底
        String mac = MacResolveUtil.resolveClientMac(request, dto.getMacAddress());
//        String mac = DeviceMacUtil.getPrimaryMacAddress();
        if (mac == null || mac.isEmpty()) {
//            throw new RuntimeException("无法获取主控设备 MAC 地址");
            log.error("无法获取主控设备 MAC 地址");
            mac = masterMac;
        }
        log.info("主控设备MAC: {}", mac);
        mac = mac.toLowerCase();

        // 通过 MAC 反查主控设备
        IotDevice controller = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getMacAddress, mac)
                        .eq(IotDevice::getDeviceType, DEVICE_TYPE_CONTROLLER)
        );
        if (controller == null) {
            throw new RuntimeException("主控设备不存在或非主控设备: mac=" + mac);
        }

        // 通过 MQTT 与主控做一次在线 & MAC 一致性校验（如不通过会直接抛异常）
        verifyControllerOnlineAndMac(controller, mac, dto);

        // 部门/企业授权：当前用户所属部门对该主控的有效授权
        String operatorId = dto.getOperatorId();
        if (operatorId == null || operatorId.isEmpty()) {
            throw new RuntimeException("缺少操作员ID（operatorId）");
        }
        List<String> departIds = sysUserDepartLiteMapper.listDepartIdsByUserId(operatorId);
        if (departIds == null || departIds.isEmpty()) {
            throw new RuntimeException("无权限：当前用户未绑定部门");
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<IotDeviceAuth> authWrapper = new LambdaQueryWrapper<IotDeviceAuth>()
                .eq(IotDeviceAuth::getControllerId, controller.getId())
                .eq(IotDeviceAuth::getStatus, 1)
                .and(w -> w.isNull(IotDeviceAuth::getEffectiveTime).or().le(IotDeviceAuth::getEffectiveTime, now))
                .and(w -> w.isNull(IotDeviceAuth::getExpireTime).or().ge(IotDeviceAuth::getExpireTime, now))
                .in(IotDeviceAuth::getEnterpriseId, departIds);
        List<IotDeviceAuth> auths = deviceAuthMapper.selectList(authWrapper);
        if (auths == null || auths.isEmpty()) {
            throw new RuntimeException("无权限：主控设备未授权给当前用户");
        }

        // 可用机器人列表（授权绑定）
        List<UsageStatusVO.RobotBasicVO> available = new ArrayList<>();
        Set<String> allowedRobotIds = new HashSet<>();
        for (IotDeviceAuth a : auths) {
            if (a.getDeviceId() != null && !a.getDeviceId().isEmpty()) {
                allowedRobotIds.add(a.getDeviceId());
            }
        }
        for (String rid : allowedRobotIds) {
            IotDevice robot = deviceMapper.selectById(rid);
            if (robot != null && Integer.valueOf(DEVICE_TYPE_ROBOT).equals(robot.getDeviceType())) {
                UsageStatusVO.RobotBasicVO v = new UsageStatusVO.RobotBasicVO();
                v.setRobotId(robot.getId());
                v.setRobotCode(robot.getDeviceCode());
                v.setRobotName(robot.getDeviceName());
                v.setStatus(robot.getStatus());
                v.setDeviceModel(robot.getDeviceModel());
                v.setFirmwareVersion(robot.getFirmwareVersion());
                available.add(v);
            }
        }
        // 构建登录日志 DTO（公共部分）
        MasterLoginDTO logDto = new MasterLoginDTO();
        logDto.setDeviceId(controller.getId());
        logDto.setDeviceCode(controller.getDeviceCode());
        logDto.setOperatorId(dto.getOperatorId());
        logDto.setOperatorName(dto.getOperatorName());

        MasterLoginResolveVO vo = new MasterLoginResolveVO();
        vo.setController(controller);
        vo.setAvailableRobots(available);

        // 查询该主控及当前用户所绑定部门待开启且生效中的工单（已按计划开始时间升序）
        List<WorkOrder> pendingOrders = workOrderService.listPendingForControllerAndDepartments(controller.getDeviceCode(), departIds);
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            // 无待开启工单，仅写登录日志，不推送工单/机器人信息给前端
            var loginLog = recordLogin(logDto);
            vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);
            return vo;
        }

        // 取生效时间最近的第一条工单
        WorkOrder firstOrder = pendingOrders.get(0);

        // 查找该工单关联的机器人设备
        IotDevice robot = resolveWorkOrderRobot(firstOrder.getId());

        // 补全登录日志中的机器人信息
        if (robot != null) {
            logDto.setAssociatedRobotId(robot.getId());
            logDto.setAssociatedRobotCode(robot.getDeviceCode());
            // 通知设备操作的是哪台机器人
            notifyControllerWhichRobot(robot);
        }
        var loginLog = this.recordLogin(logDto);
        vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);

        // 通过 WebSocket 推送工单信息给前端
        try {
            String workOrderJson = objectMapper.writeValueAsString(firstOrder);
            deviceWebSocketServer.pushWorkOrderStart(controller.getDeviceCode(), workOrderJson);
        } catch (Exception e) {
            log.warn("[ControllerLogin] WebSocket 推送工单失败: workOrderId={}, err={}", firstOrder.getId(), e.getMessage());
        }

        // 组装返回 VO
        vo.setPendingWorkOrder(firstOrder);
        if (robot != null) {
            UsageStatusVO.RobotBasicVO current = new UsageStatusVO.RobotBasicVO();
            current.setRobotId(robot.getId());
            current.setRobotCode(robot.getDeviceCode());
            current.setRobotName(robot.getDeviceName());
            current.setStatus(robot.getStatus());
            current.setDeviceModel(robot.getDeviceModel());
            current.setFirmwareVersion(robot.getFirmwareVersion());
            vo.setCurrentRobot(current);
        }
        return vo;
    }

    /**
     * 通过 MQTT 向主控查询“当前关联设备信息”，用于：
     * 1）判断主控是否在线（未在超时时间内响应视为离线）；
     * 2）比对主控实际上报的 MAC 地址与请求侧解析的 MAC 是否一致。
     */
    private MqttMessageModel.AssociatedDeviceResponse verifyControllerOnlineAndMac(IotDevice controller,
                                                                                   String requestMac,
                                                                                   MasterLoginDTO dto) {
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future =
                masterAssociatedDevicePendingService.register(commandId);

        MqttMessageModel.AssociatedDeviceQuery query = MqttMessageModel.AssociatedDeviceQuery.builder()
                .commandId(commandId)
                .operatorId(dto.getOperatorId())
                .loginLogId(null)
                .timestamp(System.currentTimeMillis())
                .build();

        String topic = String.format(DeviceConstant.MqttTopic.ASSOCIATED_DEVICE_QUERY, controller.getDeviceCode());
        try {
            mqttPublisher.publishToDevice(controller.getDeviceCode(), topic,
                    objectMapper.writeValueAsString(query), 1);
        } catch (Exception e) {
            masterAssociatedDevicePendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("主控在线状态查询失败，请稍后重试", e);
        }

        MqttMessageModel.AssociatedDeviceResponse resp;
        try {
            // 等待主控响应，超时则视为主控不在线
            resp = future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            masterAssociatedDevicePendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("主控设备不在线或响应超时", e);
        } catch (Exception e) {
            masterAssociatedDevicePendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("主控设备响应异常: " + e.getMessage(), e);
        }

        if (resp == null) {
            throw new RuntimeException("主控设备未返回关联设备信息");
        }
        if (resp.getCode() != 0) {
            throw new RuntimeException("主控设备返回错误: " + resp.getMessage());
        }

        String reportedMac = resp.getMacAddress();
        if (reportedMac == null || reportedMac.isEmpty()) {
            throw new RuntimeException("主控设备未上报MAC地址，无法完成校验");
        }

        if (!requestMac.equalsIgnoreCase(reportedMac)) {
            throw new RuntimeException("主控设备MAC与当前登录终端不一致，请确认是否为当前主控");
        }

        return resp;
    }

    /**
     * 通过 MQTT 通知主控设备操作的是哪台机器人
     */
    private void notifyControllerWhichRobot(IotDevice robot) {
        String commandId = UUID.randomUUID().toString();

        String topic = String.format(DeviceConstant.MqttTopic.TELEOP_ROBOT_ASSIGN, robot.getDeviceCode());
        MqttMessageModel.RobotAssignCommand assignCmd = MqttMessageModel.RobotAssignCommand.builder()
                .commandId(commandId)
                .robotCode(robot.getDeviceCode())
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            mqttPublisher.publishToDevice(robot.getDeviceCode(), topic,
                    objectMapper.writeValueAsString(assignCmd), 1);
        } catch (Exception e) {
            throw new RuntimeException("通过 MQTT 通知主控设备操作的是哪台机器人失败，请稍后重试", e);
        }

    }

    /**
     * 从工单设备绑定记录中查找对应的机器人实体。
     */
    private IotDevice resolveWorkOrderRobot(String workOrderId) {
        WorkOrderDevice bind = workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                        .eq(WorkOrderDevice::getDeviceType, "1")
                        .last("LIMIT 1")
        );
        if (bind == null) {
            return null;
        }
        // 优先使用实际设备 ID
        String deviceId = StrUtil.isNotBlank(bind.getActualDeviceId()) ? bind.getActualDeviceId() : bind.getDeviceId();
        if (deviceId != null) {
            return deviceMapper.selectById(deviceId);
        }
        return null;
    }
}
