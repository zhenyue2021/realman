package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * WebRTC 信令服务重启通知处理器（Topic: device/{deviceCode}/webrtc/restart）
 *
 * <p>WebRTC 信令服务启动时，通过此 Topic 上报，平台收到后立即经由 WebSocket
 * 推送 {@code WEBRTC_RESTART} 事件给前端，前端可据此触发重新建立信令连接等逻辑。
 *
 * <p>上报 Payload 示例：
 * <pre>
 * {
 *   "command":   "restart",
 *   "commandId": "97127fb9b78a4d00b217eeb42c0d5041",
 *   "timestamp": 1775716304420
 * }
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WebRtcRestartHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper          objectMapper;
    private final DeviceWebSocketServer webSocketServer;

    /**
     * 处理 WebRTC 信令服务重启通知
     *
     * @param deviceCode 设备编码
     * @param payload    AES 加密的 Payload
     */
    public void handle(String deviceCode, String payload) {
        try {
            String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
            MqttMessageModel.WebRtcRestart restart =
                    objectMapper.readValue(decrypted, MqttMessageModel.WebRtcRestart.class);
            log.info("[WebRtcRestart] 信令服务已重启 deviceCode={} commandId={} timestamp={}",
                    deviceCode, restart.getCommandId(), restart.getTimestamp());
            webSocketServer.pushWebRtcRestart(deviceCode, decrypted);
        } catch (Exception e) {
            log.error("[WebRtcRestart] 处理异常 deviceCode={}", deviceCode, e);
        }
    }
}
