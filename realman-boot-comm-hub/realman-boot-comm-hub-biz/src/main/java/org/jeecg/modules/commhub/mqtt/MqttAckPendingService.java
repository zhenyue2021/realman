package org.jeecg.modules.commhub.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private static final String ACK_FIELD_SET_PREFIX = "comm-hub:mqtt-ack-fields:";
    private static final String DEFAULT_ACK_TOPIC_SUFFIX = "bridge-ack";
    private static final String DEFAULT_ACK_COMMAND_ID_FIELD = "commandId";

    private final StringRedisTemplate redisTemplate;
    private final Map<String, CompletableFuture<String>> localPending = new ConcurrentHashMap<>();
    private final Map<String, AckProtocol> localProtocols = new ConcurrentHashMap<>();

    /** 注册一个等待中的 commandId，返回可 {@code get(timeout)} 的 future。 */
    public CompletableFuture<String> register(String commandId) {
        return register(commandId, DEFAULT_ACK_TOPIC_SUFFIX, DEFAULT_ACK_COMMAND_ID_FIELD, 10_000L);
    }

    /** 注册等待项，并记录本次下行期望的 ACK Topic/字段，供不同设备族的 ACK 解析使用。 */
    public CompletableFuture<String> register(String commandId, String ackTopicSuffix, String ackCommandIdField, Long ttlMs) {
        CompletableFuture<String> future = new CompletableFuture<>();
        AckProtocol protocol = AckProtocol.of(ackTopicSuffix, ackCommandIdField);
        localPending.put(commandId, future);
        localProtocols.put(commandId, protocol);
        rememberAckField(protocol, ttlMs);
        return future;
    }

    /** 超时或异常时清理本地挂起项，避免内存泄漏。 */
    public void abandon(String commandId) {
        localPending.remove(commandId);
        localProtocols.remove(commandId);
    }

    /** 返回指定 ACK Topic 当前活跃等待项声明过的 commandId 字段名。 */
    public Set<String> ackCommandIdFields(String ackTopicSuffix) {
        String normalizedTopic = normalize(ackTopicSuffix, DEFAULT_ACK_TOPIC_SUFFIX);
        Set<String> fields = new LinkedHashSet<>();
        fields.add(DEFAULT_ACK_COMMAND_ID_FIELD);
        localProtocols.values().stream()
                .filter(protocol -> protocol.getAckTopicSuffix().equals(normalizedTopic))
                .map(AckProtocol::getAckCommandIdField)
                .forEach(fields::add);
        try {
            Set<String> redisFields = redisTemplate.opsForSet().members(ACK_FIELD_SET_PREFIX + normalizedTopic);
            if (redisFields != null) {
                fields.addAll(redisFields);
            }
        } catch (Exception e) {
            log.debug("[comm-hub] 读取 ACK 字段集合失败 ackTopicSuffix={}: {}", normalizedTopic, e.getMessage());
        }
        return fields;
    }

    /** 设备 ACK 到达时调用（在收到 MQTT 消息的那个节点上）。跨节点广播，见类注释。 */
    public void complete(String commandId, String ackPayloadJson) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId, ackPayloadJson);
        } catch (Exception e) {
            log.warn("[comm-hub] ACK Redis 广播失败，尝试仅本地完成 commandId={}: {}", commandId, e.getMessage());
            CompletableFuture<String> future = localPending.remove(commandId);
            localProtocols.remove(commandId);
            if (future != null) {
                future.complete(ackPayloadJson);
            }
        }
    }

    /** Redis pub/sub 消息回调，见 {@code RedisPendingListenerConfig}（跨包调用，故为 public）。 */
    public void onRedisMessage(String commandId, String ackPayloadJson) {
        CompletableFuture<String> future = localPending.remove(commandId);
        localProtocols.remove(commandId);
        if (future != null) {
            future.complete(ackPayloadJson);
        } else {
            log.debug("[comm-hub] ACK 到达时本地无挂起请求（已超时或非本节点持有）commandId={}", commandId);
        }
    }

    private void rememberAckField(AckProtocol protocol, Long ttlMs) {
        try {
            String key = ACK_FIELD_SET_PREFIX + protocol.getAckTopicSuffix();
            redisTemplate.opsForSet().add(key, protocol.getAckCommandIdField());
            redisTemplate.expire(key, ttlMs != null ? Math.max(ttlMs, 10_000L) : 10_000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[comm-hub] 记录 ACK 协议参数失败 ackTopicSuffix={} ackCommandIdField={}: {}",
                    protocol.getAckTopicSuffix(), protocol.getAckCommandIdField(), e.getMessage());
        }
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    @Value
    public static class AckProtocol {
        String ackTopicSuffix;
        String ackCommandIdField;

        static AckProtocol of(String ackTopicSuffix, String ackCommandIdField) {
            return new AckProtocol(
                    normalize(ackTopicSuffix, DEFAULT_ACK_TOPIC_SUFFIX),
                    normalize(ackCommandIdField, DEFAULT_ACK_COMMAND_ID_FIELD)
            );
        }
    }
}
