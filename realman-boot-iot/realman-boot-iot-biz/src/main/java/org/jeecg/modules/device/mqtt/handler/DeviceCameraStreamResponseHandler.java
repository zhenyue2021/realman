package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.DeviceCameraStreamPendingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 摄像头视频流地址响应处理器（Topic: device/{deviceCode}/camera/stream/ack）
 *
 * <p>职责：
 * <ul>
 *   <li>对机器人上报的摄像头流响应做 AES 解密</li>
 *   <li>反序列化为 {@link MqttMessageModel.CameraStreamResponse}</li>
 *   <li>根据 commandId 完成 {@link DeviceCameraStreamPendingService} 中挂起的 Future</li>
 * </ul>
 *
 * <p>调用链：
 * <pre>
 *   Web → RobotDeviceController → IotDeviceServiceImpl.getCameraStreams()
 *        → 下发 CameraStreamQuery 并在 DeviceCameraStreamPendingService 中注册 Future
 *   机器人 → device/{code}/camera/stream/ack → 本 Handler
 *        → 完成 Future，getCameraStreams() 得到摄像头流列表后返回给 Web
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DeviceCameraStreamResponseHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper          objectMapper;
    private final DeviceCameraStreamPendingService pendingService;

    public void handle(String deviceCode, String payload) {
        try {
            String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
            log.info("[DeviceCameraStreamResponseHandler] 解密成功, 设备上报消息体为: {}", decrypted);
            MqttMessageModel.CameraStreamResponse resp = objectMapper.readValue(decrypted, MqttMessageModel.CameraStreamResponse.class);
            log.info("[DeviceCameraStreamResponseHandler] 设备[{}] commandId={} code={}", deviceCode, resp.getCommandId(), resp.getCode());

            if (resp.getCode() != 0) {
                log.warn("[CameraStream] 设备[{}]响应失败 code={} msg={}", deviceCode, resp.getCode(), resp.getMessage());
                pendingService.completeExceptionally(resp.getCommandId(),
                        new RuntimeException("设备响应失败: " + resp.getMessage()));
                return;
            }

            boolean found = pendingService.complete(resp.getCommandId(),
                    resp.getCameras() != null ? resp.getCameras() : Collections.emptyList());
            if (!found) {
                log.warn("[CameraStream] 设备[{}]响应已超时或重复 commandId={}", deviceCode, resp.getCommandId());
            }
        } catch (Exception e) {
            log.error("[CameraStream] 处理响应异常 deviceCode={}", deviceCode, e);
        }
    }
}
