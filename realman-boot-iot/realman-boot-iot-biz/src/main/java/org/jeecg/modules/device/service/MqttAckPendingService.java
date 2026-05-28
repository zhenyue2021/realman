package org.jeecg.modules.device.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT ACK 集群等待基类：本地 {@link CompletableFuture} + Redis Pub/Sub 跨节点通知。
 *
 * <p>配合 EMQX {@code $share/iot-cluster/} 共享订阅：MQTT ACK 仅一个节点消费，
 * 通过 {@link #complete} 广播到持有 Future 的节点。
 *
 * @param <T> ACK/响应类型
 */
@Slf4j
public abstract class MqttAckPendingService<T> extends ClusterRedisMessageListener {

    private final ConcurrentHashMap<String, CompletableFuture<T>> localPending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    protected MqttAckPendingService(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    String channelPrefix,
                                    String logTag) {
        super(redisTemplate, channelPrefix, logTag);
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<T> register(String commandId) {
        CompletableFuture<T> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, T value) {
        try {
            publish(commandId, objectMapper.writeValueAsString(value));
            return true;
        } catch (Exception e) {
            log.error("[{}] Redis 发布失败 commandId={}", logTag, commandId, e);
            CompletableFuture<T> future = localPending.remove(commandId);
            if (future != null) {
                future.complete(value);
            }
            return false;
        }
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<T> future = localPending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String commandId = extractKeyFromChannel(message);
        CompletableFuture<T> future = localPending.remove(commandId);
        if (future != null) {
            try {
                future.complete(deserialize(message.getBody()));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }

    protected ObjectMapper objectMapper() {
        return objectMapper;
    }

    protected abstract T deserialize(byte[] body) throws Exception;

    /** 供 List 等泛型类型子类使用 */
    protected static <R> R readValue(ObjectMapper mapper, byte[] body, TypeReference<R> typeRef)
            throws Exception {
        return mapper.readValue(body, typeRef);
    }
}
