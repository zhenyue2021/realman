package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通用指令集 ACK 处理器（Topic: device/{deviceCode}/command/{cmd}/ack）
 *
 * <p>适用于：关机、急停、复位、重启等需要设备上行确认的指令。
 * Payload 解析字段约定：commandId、code、message、timestamp（可按设备端实现扩展）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceCommandAckHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;
    private final IotDeviceConfigMapper      configMapper;
    private final IotDeviceMapper            deviceMapper;

    public void handle(String deviceCode, String cmd, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        log.info("[CommandAckHandler] 解密成功, 设备上报消息体为: {}", decrypted);
        JsonNode node = objectMapper.readTree(decrypted);

        String commandId = text(node, "commandId");
        int code = node.has("code") ? node.get("code").asInt() : -1;
        String message = text(node, "message");

        String opType = toOperationType(cmd);
        log.info("[CommandAck] device={} cmd={} commandId={} code={}", deviceCode, cmd, commandId, code);

        logService.recordLog(null, deviceCode, opType,
                "设备收到指令[" + cmd + "]" + (code == 0 ? "并执行" : "失败"),
                "{commandId:" + (commandId == null ? "" : commandId) + "}",
                DeviceConstant.OperationSource.DEVICE,
                code == 0 ? "SUCCESS" : "FAIL",
                message, null, null);

        // force-feedback 指令 ACK：更新 iot_device_config 中对应记录的同步状态
        if ("force-feedback".equals(cmd)) {
            int syncStatus = code == 0 ? DeviceConstant.ConfigSyncStatus.SUCCESS
                                       : DeviceConstant.ConfigSyncStatus.FAILED;
            LocalDateTime now = LocalDateTime.now();

            // 1. 优先更新 PENDING 状态的记录
            int updated = configMapper.update(null, new LambdaUpdateWrapper<IotDeviceConfig>()
                    .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                    .eq(IotDeviceConfig::getConfigType, "force_feedback")
                    .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING)
                    .set(IotDeviceConfig::getSyncStatus, syncStatus)
                    .set(IotDeviceConfig::getSyncTime, now));

            // 2. 无 PENDING 记录且 code=0：视为设备主动上报当前配置，插入新记录
            if (updated == 0 && code == 0) {
                IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, deviceCode)
                        .eq(IotDevice::getDeviceType, DeviceConstant.DeviceType.ROBOT)
                        .last("LIMIT 1"));
                String deviceId = device != null ? device.getId() : null;
                // 从 ACK payload 中尝试解析设备上报的参数值（设备可选择附带）
                insertConfigIfAbsent(deviceId, deviceCode, "arm_level",
                        intValue(node, "armLevel"), "force_feedback", syncStatus, now);
                insertConfigIfAbsent(deviceId, deviceCode, "gripper_level",
                        intValue(node, "gripperLevel"), "force_feedback", syncStatus, now);
                log.info("[CommandAck] force-feedback 无PENDING记录，已按设备上报插入配置: device={}", deviceCode);
            } else {
                log.info("[CommandAck] force-feedback 配置同步状态已更新: device={} updated={} status={}", deviceCode, updated, syncStatus);
            }
        }
    }

    /**
     * 若该 configKey 下不存在任何记录则插入；已有记录则跳过（避免重复）。
     */
    private void insertConfigIfAbsent(String deviceId, String deviceCode,
                                      String configKey, String configValue,
                                      String configType, int syncStatus, LocalDateTime syncTime) {
        boolean exists = configMapper.exists(new LambdaQueryWrapper<IotDeviceConfig>()
                .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                .eq(IotDeviceConfig::getConfigKey, configKey));
        if (!exists) {
            IotDeviceConfig cfg = new IotDeviceConfig();
            cfg.setDeviceId(deviceId);
            cfg.setDeviceCode(deviceCode);
            cfg.setConfigKey(configKey);
            cfg.setConfigValue(configValue);
            cfg.setConfigType(configType);
            cfg.setSyncStatus(syncStatus);
            cfg.setSyncTime(syncTime);
            configMapper.insert(cfg);
        }
    }

    /** 从 JsonNode 安全读取整数字段，不存在时返回 null */
    private static String intValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * cmd → operationType 映射（统一写入操作日志）
     */
    private static String toOperationType(String cmd) {
        if (cmd == null) return DeviceConstant.OperationType.COMMAND_SEND;
        return switch (cmd) {
            case "restart" -> DeviceConstant.OperationType.REMOTE_RESTART;
            case "emergency-stop" -> DeviceConstant.OperationType.EMERGENCY_STOP;
            case "poweroff" -> DeviceConstant.OperationType.POWER_OFF;
            case "reset" -> DeviceConstant.OperationType.RESET;
            default -> DeviceConstant.OperationType.COMMAND_SEND;
        };
    }
}

