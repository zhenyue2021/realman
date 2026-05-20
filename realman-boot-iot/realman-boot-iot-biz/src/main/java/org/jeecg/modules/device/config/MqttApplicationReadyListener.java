package org.jeecg.modules.device.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * IoT HTTP 鉴权就绪后补订阅 MQTT 业务 Topic。
 *
 * <p>冷启动时 {@link MqttConfig#mqttClient()} 可能在 Tomcat/HTTP Auth 未就绪前完成首次 subscribe，
 * EMQX HTTP ACL 失败会导致平台「已连接但未订阅业务 Topic」。ApplicationReady 后再执行一次幂等重订阅。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttApplicationReadyListener {

    private final MqttConfig mqttConfig;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[MQTT] ApplicationReady，补执行 ensureSubscribed");
        mqttConfig.ensureSubscribed();
    }
}
