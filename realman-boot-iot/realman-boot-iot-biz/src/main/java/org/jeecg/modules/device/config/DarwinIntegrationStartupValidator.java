package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Darwin 集成启动校验：数采链路依赖 MQTT 下发 STS/采集指令，二者开关须一致。
 */
@Slf4j
@Component
public class DarwinIntegrationStartupValidator {

    @Value("${darwin.integration.enabled:false}")
    private boolean darwinEnabled;

    @Value("${mqtt.enabled:false}")
    private boolean mqttEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        if (!darwinEnabled) {
            return;
        }
        if (mqttEnabled) {
            log.info("[Darwin] 集成已启用，MQTT 已启用，数采链路配置正常");
            return;
        }
        log.error("""
                [Darwin] 配置不一致：darwin.integration.enabled=true 但 mqtt.enabled=false。
                OSS 授权响应 Consumer 会运行，但 CollectUrlRequestHandler / DataCollectCommandService 不会注册，
                设备侧将无法收到 STS 凭证与采集指令。请在 Nacos 中同时开启 mqtt.enabled=true。""");
    }
}
