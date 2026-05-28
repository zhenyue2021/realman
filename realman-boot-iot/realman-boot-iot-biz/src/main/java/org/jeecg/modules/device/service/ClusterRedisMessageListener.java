package org.jeecg.modules.device.service;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 集群 Redis Pub/Sub 监听器基类：统一频道前缀解析与消息发布。
 *
 * <p>Redis 仅作为跨 Pod 通知通道；具体语义由子类定义（MQTT ACK 完成、定时任务停止等）。
 */
public abstract class ClusterRedisMessageListener implements MessageListener {

    protected final StringRedisTemplate redisTemplate;
    protected final String channelPrefix;
    protected final String logTag;

    protected ClusterRedisMessageListener(StringRedisTemplate redisTemplate,
                                          String channelPrefix,
                                          String logTag) {
        this.redisTemplate = redisTemplate;
        this.channelPrefix = channelPrefix;
        this.logTag = logTag;
    }

    public String getChannelPrefix() {
        return channelPrefix;
    }

    protected String extractKeyFromChannel(Message message) {
        String channel = new String(message.getChannel());
        return channel.substring(channelPrefix.length());
    }

    protected void publish(String key, String payload) {
        redisTemplate.convertAndSend(channelPrefix + key, payload);
    }
}
