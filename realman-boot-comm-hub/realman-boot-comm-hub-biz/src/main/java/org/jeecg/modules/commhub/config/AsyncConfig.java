package org.jeecg.modules.commhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Webhook 推送专用线程池，避免慢第三方回调阻塞 MQTT 消息接收回调线程
 * （见 {@code MqttMessageDispatcher}，其自身也把耗时处理甩到线程池，这里是同一原则
 * 的延伸：Webhook 出站 HTTP 调用同样不能占用消息处理线程）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("webhookDispatchExecutor")
    public Executor webhookDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("webhook-dispatch-");
        executor.initialize();
        return executor;
    }

    /** MQTT 上行消息处理线程池，Paho 的 messageArrived 回调只做入队，不做业务处理。 */
    @Bean("mqttMessageExecutor")
    public Executor mqttMessageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("mqtt-msg-");
        executor.initialize();
        return executor;
    }
}
