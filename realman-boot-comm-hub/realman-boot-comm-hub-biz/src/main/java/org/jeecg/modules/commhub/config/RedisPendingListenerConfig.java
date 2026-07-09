package org.jeecg.modules.commhub.config;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.commhub.mqtt.MqttAckPendingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * {@link MqttAckPendingService} 的跨 Pod 广播通道：每个节点都订阅同一个 Redis
 * 模式频道，ACK 到达时集群内所有节点都会收到消息，真正持有对应本地 future 的
 * 那个节点据此完成它。独立实现，模式与 realman-boot-iot 一致但不复用其代码。
 */
@Configuration
@RequiredArgsConstructor
public class RedisPendingListenerConfig {

    private static final String CHANNEL_PATTERN = MqttAckPendingService.CHANNEL_PREFIX + "*";
    private static final String LISTENER_METHOD = "onMessage";

    private final MqttAckPendingService ackPendingService;

    @Bean
    public RedisMessageListenerContainer mqttAckPendingListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(mqttAckMessageListenerAdapter(), new PatternTopic(CHANNEL_PATTERN));
        return container;
    }

    private MessageListenerAdapter mqttAckMessageListenerAdapter() {
        MessageListenerAdapter adapter = new MessageListenerAdapter(new MqttAckRedisListener(ackPendingService), LISTENER_METHOD);
        adapter.setSerializer(new StringRedisSerializer());
        adapter.afterPropertiesSet();
        return adapter;
    }

    /** 反射调用的监听适配器目标：{@code onMessage(String message, String channel)}。 */
    @RequiredArgsConstructor
    static class MqttAckRedisListener {

        private final MqttAckPendingService ackPendingService;

        public void onMessage(String message, String channel) {
            String commandId = channel.substring(MqttAckPendingService.CHANNEL_PREFIX.length());
            ackPendingService.onRedisMessage(commandId, message);
        }
    }
}
