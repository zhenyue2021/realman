package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC 开始指令 ACK 等待服务
 *
 * <p>集群安全：通过 Redis Pub/Sub 完成跨节点 Future 通知，机制与 {@link DeviceCameraStreamPendingService} 相同。
 * <ul>
 *   <li>调用方在发送 MQTT 前调用 {@link #register(String)} 注册等待 Future</li>
 *   <li>{@link org.jeecg.modules.device.mqtt.handler.WebRtcAckHandler} 收到 ACK 后调用 {@link #complete} 发布到 Redis</li>
 *   <li>Redis 消息到达时 {@link #onMessage} 完成本地 Future，解锁等待线程</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebRtcAckPendingService implements MessageListener {

    public static final String CHANNEL_PREFIX = "iot:pending:webrtc:";

    private final ConcurrentHashMap<String, CompletableFuture<MqttMessageModel.WebRtcAck>> localPending
            = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CompletableFuture<MqttMessageModel.WebRtcAck> register(String commandId) {
        CompletableFuture<MqttMessageModel.WebRtcAck> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, MqttMessageModel.WebRtcAck ack) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId,
                    objectMapper.writeValueAsString(ack));
            return true;
        } catch (Exception e) {
            log.error("[WebRtcAckPending] Redis 发布失败 commandId={}", commandId, e);
            CompletableFuture<MqttMessageModel.WebRtcAck> future = localPending.remove(commandId);
            if (future != null) {
                future.complete(ack);
            }
            return false;
        }
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<MqttMessageModel.WebRtcAck> future = localPending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String commandId = channel.substring(CHANNEL_PREFIX.length());
        CompletableFuture<MqttMessageModel.WebRtcAck> future = localPending.remove(commandId);
        if (future != null) {
            try {
                future.complete(objectMapper.readValue(message.getBody(), MqttMessageModel.WebRtcAck.class));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
