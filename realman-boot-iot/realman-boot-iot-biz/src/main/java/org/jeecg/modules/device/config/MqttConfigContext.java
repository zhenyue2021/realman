package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * MQTT 配置上下文（解决循环依赖的工具类）
 *
 * <p>问题背景：
 * {@link MqttConfig#mqttClient()} 在 Bean 初始化时需要引用 {@link MqttMessageDispatcher}，
 * 而 MqttMessageDispatcher 又通过构造注入依赖多个 Handler，Handler 又依赖 Service，
 * Service 中可能再依赖 MqttPublisher（通过 MqttClient），形成循环依赖链：
 * <pre>
 *   MqttConfig → MqttMessageDispatcher → ... → MqttPublisher → MqttClient（MqttConfig 创建）
 * </pre>
 *
 * <p>解决方案：
 * 实现 {@link ApplicationContextAware}，在 Spring 容器初始化完成后持有 ApplicationContext 静态引用。
 * MqttConfig 的消息回调（运行时）通过本类的 {@link #getDispatcher()} 延迟获取 Dispatcher Bean，
 * 而非在构造期间注入，从而打断循环依赖。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttConfigContext implements ApplicationContextAware {

    /** Spring 容器引用（static 使 MqttConfig 的内部匿名类可访问） */
    private static ApplicationContext applicationContext;

    /**
     * Spring 容器初始化完成后回调，保存 ApplicationContext 引用
     */
    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        applicationContext = ctx;
        log.info("[MqttConfigContext] ApplicationContext 初始化完成");
    }

    /**
     * 运行时延迟获取 {@link MqttMessageDispatcher} Bean
     *
     * <p>在 MQTT 消息到达时（{@code messageArrived} 回调）调用，此时 Spring 容器已完全初始化，
     * 不存在循环依赖问题。
     *
     * @return MqttMessageDispatcher 实例，获取失败时返回 null（消息将被丢弃并记录警告）
     */
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
