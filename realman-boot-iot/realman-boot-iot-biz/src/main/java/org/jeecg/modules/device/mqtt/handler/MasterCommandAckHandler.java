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
 * 主控设备指令 ACK 处理器（Topic: master/{controllerCode}/command/{cmd}/ack）
 *
 * <p>与 {@link DeviceCommandAckHandler} 类似，但语义上区分“主控设备”与普通设备，
 * 便于后续做独立的操作类型或审计策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterCommandAckHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;
    private final IotDeviceConfigMapper      configMapper;
    private final IotDeviceMapper            deviceMapper;

    public void handle(String controllerCode, String cmd, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(controllerCode, payload);
        log.info("[MasterCommandAckHandler] 解密成功, 主控上报消息体为: {}", decrypted);
        JsonNode node = objectMapper.readTree(decrypted);

        String commandId = text(node, "commandId");
        int code = node.has("code") ? node.get("code").asInt() : -1;
        String message = text(node, "message");

        log.info("[ControllerCommandAck] controller={} cmd={} commandId={} code={}",
                controllerCode, cmd, commandId, code);

        // 目前仍使用通用 COMMAND_SEND 类型，必要时可扩展专用类型
        logService.recordLog(null, controllerCode,
                DeviceConstant.OperationType.COMMAND_SEND,
                "主控设备执行指令[" + cmd + "]" + (code == 0 ? "成功" : "失败"),
                "{commandId:" + (commandId == null ? "" : commandId) + "}",
                DeviceConstant.OperationSource.DEVICE,
                code == 0 ? "SUCCESS" : "FAIL",
                message, null, null);

        // sport-speed 指令 ACK：更新 iot_device_config 中对应记录的同步状态
        if ("sport-speed".equals(cmd)) {
            int syncStatus = code == 0 ? DeviceConstant.ConfigSyncStatus.SUCCESS
                                       : DeviceConstant.ConfigSyncStatus.FAILED;
            LocalDateTime now = LocalDateTime.now();

            // 1. 优先更新 PENDING 状态的记录
            int updated = configMapper.update(null, new LambdaUpdateWrapper<IotDeviceConfig>()
                    .eq(IotDeviceConfig::getDeviceCode, controllerCode)
                    .eq(IotDeviceConfig::getConfigType, "sport_speed")
                    .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING)
                    .set(IotDeviceConfig::getSyncStatus, syncStatus)
                    .set(IotDeviceConfig::getSyncTime, now));

            // 2. 无 PENDING 记录且 code=0：视为设备主动上报当前配置，插入新记录
            if (updated == 0 && code == 0) {
                IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, controllerCode)
                        .eq(IotDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER)
                        .last("LIMIT 1"));
                String deviceId = device != null ? device.getId() : null;
                insertConfigIfAbsent(deviceId, controllerCode, "move_speed_level",
                        intValue(node, "moveSpeedLevel"), "sport_speed", syncStatus, now);
                insertConfigIfAbsent(deviceId, controllerCode, "lift_speed_level",
                        intValue(node, "liftSpeedLevel"), "sport_speed", syncStatus, now);
                log.info("[MasterCommandAck] sport-speed 无PENDING记录，已按设备上报插入配置: controller={}", controllerCode);
            } else {
                log.info("[MasterCommandAck] sport-speed 配置同步状态已更新: controller={} updated={} status={}", controllerCode, updated, syncStatus);
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

    /** 从 JsonNode 安全读取字段值，不存在时返回 null */
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
}

