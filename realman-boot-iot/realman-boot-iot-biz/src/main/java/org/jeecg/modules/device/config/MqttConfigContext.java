package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * MQTT 配置上下文，用于解决循环依赖
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttConfigContext implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        applicationContext = ctx;
        log.info("[MqttConfigContext] ApplicationContext 初始化完成");
    }

    public static MqttMessageDispatcher getDispatcher() {
        if (applicationContext == null) {
            log.warn("[MqttConfigContext] ApplicationContext 未初始化");
            return null;
        }
        try {
            return applicationContext.getBean(MqttMessageDispatcher.class);
        } catch (BeansException e) {
            log.warn("[MqttConfigContext] 无法获取 MqttMessageDispatcher: {}", e.getMessage());
            return null;
        }
    }

}
