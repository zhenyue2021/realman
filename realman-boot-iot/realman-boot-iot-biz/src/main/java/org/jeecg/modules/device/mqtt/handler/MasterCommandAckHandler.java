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
import org.jeecg.modules.device.service.ForceFeedbackQueryPendingService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
import org.jeecg.modules.device.service.SportSpeedQueryPendingService;
import org.jeecg.modules.device.vo.ForceFeedbackVO;
import org.jeecg.modules.device.vo.SportSpeedVO;
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

    private final CommandEncryptService           encryptService;
    private final ObjectMapper                    objectMapper;
    private final IDeviceOperationLogService      logService;
    private final IIotDeviceCommandRecordService  commandRecordService;
    private final IotDeviceConfigMapper           configMapper;
    private final IotDeviceMapper                 deviceMapper;
    private final SportSpeedQueryPendingService   sportSpeedPending;
    private final ForceFeedbackQueryPendingService forceFeedbackPending;

    public void handle(String deviceCode, String cmd, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        log.info("[MasterCommandAckHandler] 解密成功, 主控上报消息体为: {}", decrypted);
        JsonNode node = objectMapper.readTree(decrypted);

        String commandId = text(node, "commandId");
        int code = node.has("code") ? node.get("code").asInt() : -1;
        String message = text(node, "message");

        log.info("[ControllerCommandAck] controller={} cmd={} commandId={} code={}",
                deviceCode, cmd, commandId, code);

        String deviceId = resolveDeviceId(deviceCode);
        logService.recordLog(deviceId, deviceCode,
                DeviceConstant.OperationType.COMMAND_SEND,
                "主控设备执行指令[" + cmd + "]" + (code == 0 ? "成功" : "失败"),
                "{\"commandId\":\"" + (commandId == null ? "" : commandId) + "\"}",
                DeviceConstant.OperationSource.DEVICE,
                code == 0 ? "SUCCESS" : "FAIL",
                message, null, null);
        commandRecordService.ack(commandId, code == 0, message, decrypted);

        switch (cmd) {
            case "sport-speed":
                handleSportSpeedAck(deviceId, deviceCode, node, code, commandId);
                break;
            case "force-feedback":
                handleForceFeedbackAck(deviceId, deviceCode, node, code, commandId);
                break;
            default:
                log.warn("[MasterCommandAckHandler] 未知指令类型, 忽略: controller={} cmd={}", deviceCode, cmd);
        }
    }

    /** sport-speed 指令 ACK 处理 */
    private void handleSportSpeedAck(String deviceId, String deviceCode, JsonNode node, int code, String commandId) {
        LocalDateTime now = LocalDateTime.now();
        // 目前仅可设置移动速度
        syncConfigStatus(deviceId, deviceCode, "0", "move_speed_level", "moveSpeedLevel", node, code, now);

        // 若是查询指令（moveSpeedLevel/liftSpeedLevel 由设备回填），完成挂起的 Future
        if (code == 0 && commandId != null) {
            sportSpeedPending.complete(commandId,
                    new SportSpeedVO(intNode(node, "moveSpeedLevel"), intNode(node, "liftSpeedLevel")));
        }
    }

    /** force-feedback 指令 ACK 处理 */
    private void handleForceFeedbackAck(String deviceId, String deviceCode, JsonNode node, int code, String commandId) {
        LocalDateTime now = LocalDateTime.now();
        // 目前仅可设置力
        syncConfigStatus(deviceId, deviceCode, "0", "arm_level", "armLevel", node, code, now);

        // 若是查询指令（armLevel/gripperLevel 由设备回填），完成挂起的 Future
        if (code == 0 && commandId != null) {
            forceFeedbackPending.complete(commandId,
                    new ForceFeedbackVO(intNode(node, "armLevel"), intNode(node, "gripperLevel")));
        }
    }

    /**
     * 通用配置同步逻辑：
     * 1. 优先更新 PENDING 状态的记录；
     * 2. 无 PENDING 记录且 code=0：视为设备主动上报，按 configKey/nodeField 插入新记录。
     */
    private void syncConfigStatus(String deviceId, String deviceCode, String configType,
                                  String configKey, String nodeField,
                                  JsonNode node, int code, LocalDateTime now) {
        int syncStatus = code == 0 ? DeviceConstant.ConfigSyncStatus.SUCCESS
                                   : DeviceConstant.ConfigSyncStatus.FAILED;

        int updated = configMapper.update(null, new LambdaUpdateWrapper<IotDeviceConfig>()
                .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                .eq(IotDeviceConfig::getConfigType, configType)
                .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING)
                .set(IotDeviceConfig::getSyncStatus, syncStatus)
                .set(IotDeviceConfig::getSyncTime, now));

        if (updated == 0 && code == 0) {
            insertConfigIfAbsent(deviceId, deviceCode,
                    configKey, intValue(node, nodeField), configType, syncStatus, now);
            log.info("[MasterCommandAck] {} 无PENDING记录，已按设备上报插入配置: controller={}", configType, deviceCode);
        } else {
            log.info("[MasterCommandAck] {} 配置同步状态已更新: controller={} updated={} status={}", configType, deviceCode, updated, syncStatus);
        }
    }

    /** 查询主控设备 ID */
    private String resolveDeviceId(String deviceCode) {
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .eq(IotDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER)
                .last("LIMIT 1"));
        return device != null ? device.getId() : null;
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

    /** 从 JsonNode 安全读取 Integer，不存在时返回 null */
    private static Integer intNode(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    /** 从 JsonNode 安全读取字段值（转 String），不存在时返回 null */
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

