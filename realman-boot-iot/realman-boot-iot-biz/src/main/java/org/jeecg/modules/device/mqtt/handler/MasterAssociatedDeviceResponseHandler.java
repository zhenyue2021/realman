package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.MasterAssociatedDevicePendingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 主控关联设备信息响应处理器（Topic: master/{controllerCode}/teleop/associated-device/ack）
 *
 * <p>职责：
 * <ul>
 *   <li>对主控上报响应做 AES 解密</li>
 *   <li>反序列化为 {@link MqttMessageModel.AssociatedDeviceResponse}</li>
 *   <li>按 commandId 完成等待中的 Future（由 Web 接口同步等待）</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MasterAssociatedDeviceResponseHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final MasterAssociatedDevicePendingService pendingService;

    public void handle(String controllerCode, String payload) {
        try {
            String decrypted = encryptService.decryptFromDevice(controllerCode, payload);
            MqttMessageModel.AssociatedDeviceResponse resp =
                    objectMapper.readValue(decrypted, MqttMessageModel.AssociatedDeviceResponse.class);

            boolean completed = pendingService.complete(resp.getCommandId(), resp);
            if (!completed) {
                log.warn("[MasterAssociatedDeviceResponseHandler] no pending future, controllerCode={}, commandId={}",
                        controllerCode, resp.getCommandId());
            }
        } catch (Exception e) {
            log.error("[MasterAssociatedDeviceResponseHandler] handle failed controllerCode={}", controllerCode, e);
        }
    }
}
