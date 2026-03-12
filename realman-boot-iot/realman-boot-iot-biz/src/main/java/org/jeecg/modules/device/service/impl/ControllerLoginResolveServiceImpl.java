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
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.ControllerAssociatedDevicePendingService;
import org.jeecg.modules.device.service.IControllerLoginLogService;
import org.jeecg.modules.device.service.IControllerLoginResolveService;
import org.jeecg.modules.device.util.MacResolveUtil;
import org.jeecg.modules.device.vo.TeleopLoginResolveVO;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControllerLoginResolveServiceImpl implements IControllerLoginResolveService {

    private static final int DEVICE_TYPE_CONTROLLER = 2;
    private static final int DEVICE_TYPE_ROBOT = 1;

    private final ControllerAssociatedDevicePendingService pendingService;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final IControllerLoginLogService controllerLoginLogService;

    @Override
    public TeleopLoginResolveVO resolve(HttpServletRequest request, ControllerLoginDTO dto) {
        if (dto == null) {
            throw new RuntimeException("请求体不能为空");
        }

        String tenantId = request.getHeader("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }
        boolean superAdmin = false;

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

        // 下发 query 并等待 response
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future =
                pendingService.register(commandId);
        try {
            MqttMessageModel.AssociatedDeviceQuery query = MqttMessageModel.AssociatedDeviceQuery.builder()
                    .commandId(commandId)
                    .operatorId(dto.getOperatorId())
                    .loginLogId(null)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String topic = String.format(DeviceConstant.MqttTopic.ASSOCIATED_DEVICE_QUERY, controller.getDeviceCode());
            mqttPublisher.publishToDevice(controller.getDeviceCode(), topic, objectMapper.writeValueAsString(query), 1);
        } catch (Exception e) {
            pendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("下发主控关联设备查询指令失败: " + e.getMessage(), e);
        }

        MqttMessageModel.AssociatedDeviceResponse resp;
        try {
            resp = future.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("等待主控响应超时（5s），设备未响应");
        } catch (Exception e) {
            throw new RuntimeException("获取主控关联设备信息失败: " + e.getMessage(), e);
        }

        if (resp == null) {
            throw new RuntimeException("主控响应为空");
        }
        if (resp.getCode() != 0) {
            throw new RuntimeException("主控响应失败: " + (resp.getMessage() != null ? resp.getMessage() : resp.getCode()));
        }
        if (resp.getMacAddress() == null || resp.getMacAddress().isEmpty()) {
            throw new RuntimeException("主控未返回 MAC 地址");
        }
        if (!mac.equalsIgnoreCase(resp.getMacAddress())) {
            throw new RuntimeException("主控上报的 MAC 与当前登录 MAC 不一致");
        }
        if (resp.getRobotCode() == null || resp.getRobotCode().isEmpty()) {
            throw new RuntimeException("主控未返回 robotCode");
        }

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
        if (!superAdmin && (auths == null || auths.isEmpty())) {
            throw new RuntimeException("无权限：主控设备未授权给当前用户");
        }

        // 可用机器人列表（授权绑定）
        List<UsageStatusVO.RobotBasicVO> available = new ArrayList<>();
        Set<String> allowedRobotCodes = new HashSet<>();
        Set<String> allowedRobotIds = new HashSet<>();
        if (auths != null) {
            for (IotDeviceAuth a : auths) {
                if (a.getDeviceId() != null && !a.getDeviceId().isEmpty()) {
                    allowedRobotIds.add(a.getDeviceId());
                }
                if (a.getDeviceCode() != null && !a.getDeviceCode().isEmpty()) {
                    allowedRobotCodes.add(a.getDeviceCode());
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
                    allowedRobotCodes.add(robot.getDeviceCode());
                }
            }
        }

        // 校验响应机器人在授权范围内
        if (!superAdmin && !allowedRobotCodes.contains(resp.getRobotCode())
                && (resp.getRobotId() == null || !allowedRobotIds.contains(resp.getRobotId()))) {
            throw new RuntimeException("无权限：当前机器人不在授权绑定范围内");
        }

        // 写登录日志（校验通过后）
        ControllerLoginDTO logDto = new ControllerLoginDTO();
        logDto.setControllerId(controller.getId());
        logDto.setControllerCode(controller.getDeviceCode());
        logDto.setOperatorId(dto.getOperatorId());
        logDto.setOperatorName(dto.getOperatorName());

        // 机器人信息补全（用于写日志/返回）
        IotDevice robot = null;
        if (resp.getRobotId() != null && !resp.getRobotId().isEmpty()) {
            robot = deviceMapper.selectById(resp.getRobotId());
        }
        if (robot == null) {
            robot = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getDeviceCode, resp.getRobotCode())
                    .eq(IotDevice::getDeviceType, DEVICE_TYPE_ROBOT));
        }
        if (robot == null) {
            throw new RuntimeException("机器人设备不存在: " + resp.getRobotCode());
        }
        logDto.setAssociatedRobotId(robot.getId());
        logDto.setAssociatedRobotCode(robot.getDeviceCode());
        var loginLog = controllerLoginLogService.recordLogin(logDto);

        TeleopLoginResolveVO vo = new TeleopLoginResolveVO();
        vo.setLoginLogId(loginLog != null ? loginLog.getId() : null);
        vo.setController(controller);
        UsageStatusVO.RobotBasicVO current = new UsageStatusVO.RobotBasicVO();
        current.setRobotId(robot.getId());
        current.setRobotCode(robot.getDeviceCode());
        current.setRobotName(robot.getDeviceName());
        current.setStatus(robot.getStatus());
        current.setDeviceModel(robot.getDeviceModel());
        current.setFirmwareVersion(robot.getFirmwareVersion());
        vo.setCurrentRobot(current);
        vo.setAvailableRobots(available);
        return vo;
    }
}

