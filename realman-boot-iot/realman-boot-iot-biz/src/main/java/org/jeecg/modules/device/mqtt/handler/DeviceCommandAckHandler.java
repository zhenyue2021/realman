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
import org.jeecg.modules.device.vo.ForceFeedbackVO;
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
    private final IotDeviceConfigMapper           configMapper;
    private final IotDeviceMapper                 deviceMapper;

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

