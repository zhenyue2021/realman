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
 * SLAM 指令首次响应等待服务
 *
 * <p>集群安全：通过 Redis Pub/Sub 完成跨节点 Future 通知，详见 {@link DeviceCameraStreamPendingService}。
 * <ul>
 *   <li>由 {@link IIotSlamCommandService#sendCommand} 在发送 MQTT 前调用 {@link #register(String)} 注册</li>
 *   <li>由 {@link IIotSlamCommandService#handleAck} 收到第一次 ack 后调用 {@link #complete} → 发布到 Redis</li>
 *   <li>Redis 消息到达时 {@link #onMessage} 完成本地 Future，解锁 sendCommand 等待</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlamCommandPendingService implements MessageListener {

    public static final String CHANNEL_PREFIX = "iot:pending:slam-cmd:";

    private final ConcurrentHashMap<String, CompletableFuture<MqttMessageModel.SlamAck>> localPending
            = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CompletableFuture<MqttMessageModel.SlamAck> register(String commandId) {
        CompletableFuture<MqttMessageModel.SlamAck> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, MqttMessageModel.SlamAck ack) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId,
                    objectMapper.writeValueAsString(ack));
            return true;
        } catch (Exception e) {
            log.error("[SlamCmdPending] Redis 发布失败: commandId={}", commandId, e);
            CompletableFuture<MqttMessageModel.SlamAck> future = localPending.remove(commandId);
            if (future != null) { future.complete(ack); }
            return false;
        }
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<MqttMessageModel.SlamAck> future = localPending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String commandId = channel.substring(CHANNEL_PREFIX.length());
        CompletableFuture<MqttMessageModel.SlamAck> future = localPending.remove(commandId);
        if (future != null) {
            try {
                future.complete(objectMapper.readValue(message.getBody(), MqttMessageModel.SlamAck.class));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
