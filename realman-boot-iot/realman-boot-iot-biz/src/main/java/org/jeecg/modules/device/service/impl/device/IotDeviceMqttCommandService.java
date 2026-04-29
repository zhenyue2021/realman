package org.jeecg.modules.device.service.impl.device;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.dto.MasterControlParamsDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备 MQTT 下行指令：通用指令、主控参数、查询类指令。
 * 遥操作编排由 {@link IotDeviceTeleopService} 负责。
 * 事务由调用方 {@link org.jeecg.modules.device.service.impl.IotDeviceServiceImpl} 统一控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceMqttCommandService {

    private final IotDeviceSupport deviceSupport;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;

    public String sendCommand(String deviceId, String cmd, String reason, String operator) {
        IotDevice device = deviceSupport.require(deviceId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus()) {
            throw new RuntimeException("设备不在线");
        }
        String commandId = IdUtil.fastSimpleUUID();
        try {
            String topic = String.format("device/%s/command/%s", device.getDeviceCode(), cmd);
            Object body;
            long now = System.currentTimeMillis();
            switch (cmd) {
                case "restart" -> body = MqttMessageModel.RemoteRestartCommand.builder()
                        .commandId(commandId).reason(reason).timestamp(now).build();
                case "emergency-stop" -> body = MqttMessageModel.EmergencyStopCommand.builder()
                        .commandId(commandId).reason(reason).timestamp(now).build();
                default -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("commandId", commandId);
                    m.put("cmd", cmd);
                    m.put("reason", reason);
                    m.put("timestamp", now);
                    body = m;
                }
            }
            String payload = objectMapper.writeValueAsString(body);
            mqttPublisher.publishToDevice(device.getDeviceCode(), topic, payload, MqttConstant.MQTT_QOS.QOS_1);

            String opType = mapCommandToOperationType(cmd);
            String desc = "发送指令[" + cmd + "]" + (reason != null ? (": " + reason) : "");
            logService.recordLog(deviceId, device.getDeviceCode(),
                    opType,
                    desc, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
        } catch (Exception e) {
            throw new RuntimeException("发送指令[" + cmd + "]失败: " + e.getMessage());
        }
        return commandId;
    }

    public void remoteRestart(String deviceId, String reason, String operator) {
        sendCommand(deviceId, "restart", reason, operator);
    }

    public void emergencyStop(String deviceId, String reason, String operator) {
        sendCommand(deviceId, "emergency-stop", reason, operator);
    }

    public void applyMasterControlParams(IotDevice controller, MasterControlParamsDTO dto) {
        sendMasterForceFeedbackCommand(controller, dto.getArmLevel(), dto.getGripperLevel(), dto.getOperator());
        sendMasterSportSpeedCommand(controller, dto.getMoveSpeedLevel(), dto.getLiftSpeedLevel(), dto.getOperator());
    }

    public String sendMasterForceFeedbackCommand(IotDevice master,
                                                 Integer armLevel,
                                                 Integer gripperLevel,
                                                 String operator) {
        if (DeviceConstant.DeviceStatus.ONLINE != master.getStatus()) {
            throw new RuntimeException("主控设备不在线");
        }
        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            MqttMessageModel.MasterForceFeedbackCommand cmd = MqttMessageModel.MasterForceFeedbackCommand.builder()
                    .commandId(commandId)
                    .armLevel(armLevel)
                    .gripperLevel(gripperLevel)
                    .timestamp(now)
                    .build();
            String payload = objectMapper.writeValueAsString(cmd);
            String topic = String.format(DeviceConstant.MqttTopic.MASTER_FORCE_FEEDBACK, master.getDeviceCode());
            mqttPublisher.publishToDevice(master.getDeviceCode(), topic, payload, MqttConstant.MQTT_QOS.QOS_1);

            String desc = "设置力反馈参数: armLevel=" + armLevel + ", gripperLevel=" + gripperLevel;
            logService.recordLog(master.getId(), master.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    desc, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
        } catch (Exception e) {
            throw new RuntimeException("发送力反馈指令失败: " + e.getMessage(), e);
        }
        return commandId;
    }

    public String sendMasterSportSpeedCommand(IotDevice controller,
                                              Integer moveSpeedLevel,
                                              Integer liftSpeedLevel,
                                              String operator) {
        if (DeviceConstant.DeviceStatus.ONLINE != controller.getStatus()) {
            throw new RuntimeException("主控设备不在线");
        }
        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            MqttMessageModel.MasterSportSpeedCommand cmd = MqttMessageModel.MasterSportSpeedCommand.builder()
                    .commandId(commandId)
                    .moveSpeedLevel(moveSpeedLevel)
                    .liftSpeedLevel(liftSpeedLevel)
                    .timestamp(now)
                    .build();
            String payload = objectMapper.writeValueAsString(cmd);
            String topic = String.format(DeviceConstant.MqttTopic.MASTER_SPORT_SPEED, controller.getDeviceCode());
            mqttPublisher.publishToDevice(controller.getDeviceCode(), topic, payload, MqttConstant.MQTT_QOS.QOS_1);

            String desc = "设置运动与安全参数: moveSpeedLevel=" + moveSpeedLevel + ", liftSpeedLevel=" + liftSpeedLevel;
            logService.recordLog(controller.getId(), controller.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    desc, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
        } catch (Exception e) {
            throw new RuntimeException("发送运动与安全参数指令失败: " + e.getMessage(), e);
        }
        return commandId;
    }

    public void queryMasterForceFeedback(String deviceId) {
        IotDevice device = deviceSupport.require(deviceId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus()) {
            throw new RuntimeException("主控设备不在线");
        }
        try {
            String commandId = IdUtil.fastSimpleUUID();
            MqttMessageModel.MasterForceFeedbackCommand cmd = MqttMessageModel.MasterForceFeedbackCommand.builder()
                    .commandId(commandId)
                    .armLevel(null)
                    .gripperLevel(null)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String payload = objectMapper.writeValueAsString(cmd);
            String topic = String.format(DeviceConstant.MqttTopic.MASTER_FORCE_FEEDBACK, device.getDeviceCode());
            mqttPublisher.publishToDevice(device.getDeviceCode(), topic, payload, MqttConstant.MQTT_QOS.QOS_1);
            logService.recordLog(device.getId(), device.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "查询力反馈参数", "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, null, null);
        } catch (Exception e) {
            throw new RuntimeException("发送力反馈查询指令失败: " + e.getMessage(), e);
        }
    }

    public void queryMasterSportSpeed(String controllerId) {
        IotDevice device = deviceSupport.require(controllerId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus()) {
            throw new RuntimeException("主控设备不在线");
        }
        try {
            String commandId = IdUtil.fastSimpleUUID();
            MqttMessageModel.MasterSportSpeedCommand cmd = MqttMessageModel.MasterSportSpeedCommand.builder()
                    .commandId(commandId)
                    .moveSpeedLevel(null)
                    .liftSpeedLevel(null)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String payload = objectMapper.writeValueAsString(cmd);
            String topic = String.format(DeviceConstant.MqttTopic.MASTER_SPORT_SPEED, device.getDeviceCode());
            mqttPublisher.publishToDevice(device.getDeviceCode(), topic, payload, MqttConstant.MQTT_QOS.QOS_1);
            logService.recordLog(device.getId(), device.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "查询运动速度参数", "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, null, null);
        } catch (Exception e) {
            throw new RuntimeException("发送运动速度查询指令失败: " + e.getMessage(), e);
        }
    }

    private static String mapCommandToOperationType(String cmd) {
        if (cmd == null) {
            return DeviceConstant.OperationType.COMMAND_SEND;
        }
        return switch (cmd) {
            case "restart" -> DeviceConstant.OperationType.REMOTE_RESTART;
            case "emergency-stop" -> DeviceConstant.OperationType.EMERGENCY_STOP;
            case "poweroff" -> DeviceConstant.OperationType.POWER_OFF;
            case "reset" -> DeviceConstant.OperationType.RESET;
            default -> DeviceConstant.OperationType.COMMAND_SEND;
        };
    }
}
