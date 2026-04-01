package org.jeecg.modules.device.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摄像头流地址查询等待服务
 *
 * <p>集群安全：调用方持有本地 CompletableFuture，通过 Redis Pub/Sub 完成跨节点通知。
 * <ul>
 *   <li>任意节点处理 MQTT ack → 调用 {@link #complete} → 发布到 Redis 频道</li>
 *   <li>Redis 消息到达每个节点 → {@link #onMessage} 检查本地 map，命中则完成 Future</li>
 * </ul>
 * 配合 EMQX {@code $share} 共享订阅使用，MQTT 消息只有一个节点消费，Redis 广播确保 Future 所在节点能收到通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCameraStreamPendingService implements MessageListener {

    public static final String CHANNEL_PREFIX = "iot:pending:camera:";

    private final ConcurrentHashMap<String, CompletableFuture<List<MqttMessageModel.CameraInfo>>> localPending
            = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CompletableFuture<List<MqttMessageModel.CameraInfo>> register(String commandId) {
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    /**
     * 通过 Redis Pub/Sub 广播结果，所有节点收到后各自检查本地 map 并完成 Future。
     * 保持原签名，Handler 调用方无需修改。
     */
    public boolean complete(String commandId, List<MqttMessageModel.CameraInfo> cameras) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId,
                    objectMapper.writeValueAsString(cameras));
            return true;
        } catch (Exception e) {
            log.error("[CameraStreamPending] Redis 发布失败: commandId={}", commandId, e);
            // 降级：直接完成本地 future（单机可用，集群下仅当前节点受益）
            CompletableFuture<List<MqttMessageModel.CameraInfo>> future = localPending.remove(commandId);
            if (future != null) { future.complete(cameras); }
            return false;
        }
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future = localPending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    /** Redis 消息到达：若本节点持有该 commandId 的 Future 则完成它 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String commandId = channel.substring(CHANNEL_PREFIX.length());
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future = localPending.remove(commandId);
        if (future != null) {
            try {
                List<MqttMessageModel.CameraInfo> cameras = objectMapper.readValue(
                        message.getBody(), new TypeReference<>() {});
                future.complete(cameras);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
