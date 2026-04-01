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
 * 主控"当前关联设备信息"查询等待服务
 *
 * <p>集群安全：通过 Redis Pub/Sub 完成跨节点 Future 通知，详见 {@link DeviceCameraStreamPendingService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterAssociatedDevicePendingService implements MessageListener {

    public static final String CHANNEL_PREFIX = "iot:pending:associated-device:";

    private final ConcurrentHashMap<String, CompletableFuture<MqttMessageModel.AssociatedDeviceResponse>> localPending
            = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> register(String commandId) {
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, MqttMessageModel.AssociatedDeviceResponse resp) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId,
                    objectMapper.writeValueAsString(resp));
            return true;
        } catch (Exception e) {
            log.error("[AssociatedDevicePending] Redis 发布失败: commandId={}", commandId, e);
            CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = localPending.remove(commandId);
            if (future != null) { future.complete(resp); }
            return false;
        }
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = localPending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String commandId = channel.substring(CHANNEL_PREFIX.length());
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = localPending.remove(commandId);
        if (future != null) {
            try {
                future.complete(objectMapper.readValue(message.getBody(),
                        MqttMessageModel.AssociatedDeviceResponse.class));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
