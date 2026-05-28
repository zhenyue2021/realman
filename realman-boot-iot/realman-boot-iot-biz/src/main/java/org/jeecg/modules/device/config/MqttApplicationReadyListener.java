package org.jeecg.modules.device.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.emqx.DeviceOnlineReconcileService;
import org.jeecg.modules.device.emqx.EmqxManagementClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * IoT HTTP 鉴权就绪后补订阅 MQTT 业务 Topic。
 *
 * <p>常见根因：EMQX Built-in 用户 {@code iot-platform} 先于 HTTP Auth 匹配，Session 无 superuser，
 * {@code $SYS/#} SUBACK=135。ApplicationReady 后先通过 Management API 标记 superuser，再重建 MQTT 连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttApplicationReadyListener {

    private final MqttConfig mqttConfig;
    private final EmqxManagementClient emqxManagementClient;
    private final DeviceOnlineReconcileService reconcileService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[MQTT] ApplicationReady，修复 $SYS superuser 并重建 MQTT 连接");
        emqxManagementClient.ensurePlatformSuperuser();
        mqttConfig.reconnectAfterHttpReady();
        reconcileService.reconcileOnStartup();
    }
}
