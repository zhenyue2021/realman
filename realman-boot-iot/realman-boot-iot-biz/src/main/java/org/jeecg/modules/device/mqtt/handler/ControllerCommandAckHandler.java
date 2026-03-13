package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.stereotype.Component;

/**
 * 主控设备指令 ACK 处理器（Topic: master/{controllerCode}/command/{cmd}/ack）
 *
 * <p>与 {@link DeviceCommandAckHandler} 类似，但语义上区分“主控设备”与普通设备，
 * 便于后续做独立的操作类型或审计策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerCommandAckHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;

    public void handle(String controllerCode, String cmd, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(controllerCode, payload);
        log.info("[ControllerCommandAckHandler] 解密成功, 主控上报消息体为: {}", decrypted);
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
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isEmpty()) ? null : s;
    }
}

