package org.jeecg.modules.device.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.handler.DeviceOnlineOfflineHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * EMQX HTTP Auth 鉴权成功后的设备上线处理。
 *
 * <p>当 {@code $SYS/.../connected} 订阅失败（SUBACK 135）或未投递时，
 * 通过 Auth 回调触发与 $SYS 相同的上线副作用（DB、Darwin、WebSocket 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DeviceMqttAuthConnectService {

    private final DeviceOnlineOfflineHandler onlineOfflineHandler;

    @Async("deviceTaskExecutor")
    public void onDeviceAuthSuccess(String deviceCode) {
        try {
            onlineOfflineHandler.handleDeviceConnectedFromAuth(deviceCode);
        } catch (Exception e) {
            log.warn("[MqttAuth] 设备上线处理失败 deviceCode={}", deviceCode, e);
        }
    }
}
