package org.jeecg.modules.commhub.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * publish-and-wait 的跨 Pod ACK 协调服务，独立实现（不引用 realman-boot-iot），
 * 模式与其一致：本地 {@link CompletableFuture} 挂起等待，设备 ACK 到达的节点通过
 * Redis pub/sub 广播给集群内所有节点，真正持有该 future 的节点据此完成它——即便
 * MQTT 连接与发起请求的不是同一个 Pod 也能正确唤醒。见设备通信中台详细设计 4.3.1。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttAckPendingService {

    /** 跨包引用（见 {@code RedisPendingListenerConfig}），故为 public。 */
    public static final String CHANNEL_PREFIX = "comm-hub:mqtt-ack:";

    private final StringRedisTemplate redisTemplate;
    private final Map<String, CompletableFuture<String>> localPending = new ConcurrentHashMap<>();

    /** 注册一个等待中的 commandId，返回可 {@code get(timeout)} 的 future。 */
    public CompletableFuture<String> register(String commandId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    /** 超时或异常时清理本地挂起项，避免内存泄漏。 */
    public void abandon(String commandId) {
        localPending.remove(commandId);
    }

    /** 设备 ACK 到达时调用（在收到 MQTT 消息的那个节点上）。跨节点广播，见类注释。 */
    public void complete(String commandId, String ackPayloadJson) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId, ackPayloadJson);
        } catch (Exception e) {
            log.warn("[comm-hub] ACK Redis 广播失败，尝试仅本地完成 commandId={}: {}", commandId, e.getMessage());
            CompletableFuture<String> future = localPending.remove(commandId);
            if (future != null) {
                future.complete(ackPayloadJson);
            }
        }
    }

    /** Redis pub/sub 消息回调，见 {@code RedisPendingListenerConfig}（跨包调用，故为 public）。 */
    public void onRedisMessage(String commandId, String ackPayloadJson) {
        CompletableFuture<String> future = localPending.remove(commandId);
        if (future != null) {
            future.complete(ackPayloadJson);
        } else {
            log.debug("[comm-hub] ACK 到达时本地无挂起请求（已超时或非本节点持有）commandId={}", commandId);
        }
    }
}
