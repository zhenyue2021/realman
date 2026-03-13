package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.ControllerLoginDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.ControllerAssociatedDevicePendingService;
import org.jeecg.modules.device.service.IControllerLoginLogService;
import org.jeecg.modules.device.service.IControllerLoginResolveService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.util.MacResolveUtil;
import org.jeecg.modules.device.vo.TeleopLoginResolveVO;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControllerLoginResolveServiceImpl implements IControllerLoginResolveService {

    private static final int DEVICE_TYPE_CONTROLLER = 2;
    private static final int DEVICE_TYPE_ROBOT = 1;

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final IWorkOrderService workOrderService;
    private final IControllerLoginLogService controllerLoginLogService;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final ControllerAssociatedDevicePendingService controllerAssociatedDevicePendingService;

    @Override
    public TeleopLoginResolveVO resolve(HttpServletRequest request, ControllerLoginDTO dto) {
        if (dto == null) {
            throw new RuntimeException("请求体不能为空");
        }

        String tenantId = request.getHeader("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }

        // 解析客户端 MAC（前端不传时，尝试通过 ARP 在内网反查）
        String mac = MacResolveUtil.resolveClientMac(request, dto.getMacAddress());
        if (mac == null || mac.isEmpty()) {
            throw new RuntimeException("无法获取主控设备 MAC 地址");
        }

        // 通过 MAC 反查主控设备
        IotDevice controller = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getMacAddress, mac)
                        .eq(IotDevice::getDeviceType, DEVICE_TYPE_CONTROLLER)
                        .eq(IotDevice::getDelFlag, 0)
        );
        if (controller == null) {
            throw new RuntimeException("主控设备不存在或非主控设备: mac=" + mac);
        }

        // 通过 MQTT 与主控做一次在线 & MAC 一致性校验（如不通过会直接抛异常）
        verifyControllerOnlineAndMac(controller, mac, dto);

        // 租户授权：当前租户对该主控的有效授权
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<IotDeviceAuth> authWrapper = new LambdaQueryWrapper<IotDeviceAuth>()
                .eq(IotDeviceAuth::getControllerId, controller.getId())
                .eq(IotDeviceAuth::getStatus, 1)
                .eq(IotDeviceAuth::getDelFlag, 0)
                .and(w -> w.isNull(IotDeviceAuth::getEffectiveTime).or().le(IotDeviceAuth::getEffectiveTime, now))
                .and(w -> w.isNull(IotDeviceAuth::getExpireTime).or().ge(IotDeviceAuth::getExpireTime, now))
                .eq(IotDeviceAuth::getSubjectType, "TENANT")
                .eq(IotDeviceAuth::getSubjectId, tenantId);
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
        ControllerLoginDTO logDto = new ControllerLoginDTO();
        logDto.setControllerId(controller.getId());
        logDto.setControllerCode(controller.getDeviceCode());
        logDto.setOperatorId(dto.getOperatorId());
        logDto.setOperatorName(dto.getOperatorName());

        TeleopLoginResolveVO vo = new TeleopLoginResolveVO();
        vo.setController(controller);
        vo.setAvailableRobots(available);

        // 查询该主控待开启且生效中的工单（已按计划开始时间升序）
        List<WorkOrder> pendingOrders = workOrderService.listPendingForController(controller.getDeviceCode());
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            // 无待开启工单，仅写登录日志，不推送工单/机器人信息给前端
            var loginLog = controllerLoginLogService.recordLogin(logDto);
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
        }
        var loginLog = controllerLoginLogService.recordLogin(logDto);
        vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);

        // 通过 WebSocket 推送工单信息给前端
        try {
            String workOrderJson = objectMapper.writeValueAsString(firstOrder);
            deviceWebSocketServer.pushWorkOrderStart(controller.getDeviceCode(), workOrderJson);
        } catch (Exception e) {
            log.warn("[ControllerLogin] WebSocket 推送工单失败: workOrderId={}, err={}", firstOrder.getId(), e.getMessage());
        }

        // 通过 MQTT 通知主控应操作的机器人
        if (robot != null) {
            try {
                MqttMessageModel.RobotAssignCommand assignCmd = MqttMessageModel.RobotAssignCommand.builder()
                        .commandId(UUID.randomUUID().toString())
                        .robotCode(robot.getDeviceCode())
                        .workOrderId(firstOrder.getId())
                        .timestamp(System.currentTimeMillis())
                        .build();
                String topic = String.format(DeviceConstant.MqttTopic.TELEOP_ROBOT_ASSIGN, controller.getDeviceCode());
                mqttPublisher.publishToDevice(controller.getDeviceCode(), topic,
                        objectMapper.writeValueAsString(assignCmd), 1);
            } catch (Exception e) {
                log.warn("[ControllerLogin] MQTT 推送机器人分配指令失败: robotCode={}, err={}",
                        robot.getDeviceCode(), e.getMessage());
            }
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
                                                                                   ControllerLoginDTO dto) {
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future =
                controllerAssociatedDevicePendingService.register(commandId);

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
            controllerAssociatedDevicePendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("主控在线状态查询失败，请稍后重试", e);
        }

        MqttMessageModel.AssociatedDeviceResponse resp;
        try {
            // 等待主控响应，超时则视为主控不在线
            resp = future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            controllerAssociatedDevicePendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("主控设备不在线或响应超时", e);
        } catch (Exception e) {
            controllerAssociatedDevicePendingService.completeExceptionally(commandId, e);
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
     * 从工单设备绑定记录中查找对应的机器人实体。
     * 优先取 actual_device，回退到计划 device。
     */
    private IotDevice resolveWorkOrderRobot(String workOrderId) {
        WorkOrderDevice bind = workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                        .eq(WorkOrderDevice::getDeviceType, "ROBOT")
                        .last("LIMIT 1")
        );
        if (bind == null) {
            return null;
        }
        // 优先使用实际设备 ID
        String deviceId = bind.getActualDeviceId() != null ? bind.getActualDeviceId() : bind.getDeviceId();
        if (deviceId != null) {
            IotDevice robot = deviceMapper.selectById(deviceId);
            if (robot != null) {
                return robot;
            }
        }
        // 回退：用实际设备 code 查
        String deviceCode = bind.getActualDeviceCode() != null ? bind.getActualDeviceCode() : bind.getDeviceCode();
        if (deviceCode != null) {
            return deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getDeviceCode, deviceCode)
                    .eq(IotDevice::getDeviceType, DEVICE_TYPE_ROBOT));
        }
        return null;
    }
}
