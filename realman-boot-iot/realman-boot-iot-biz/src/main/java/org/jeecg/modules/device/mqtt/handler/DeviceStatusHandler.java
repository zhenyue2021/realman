package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备 keepalive / status/report 处理器（Topic: device/{deviceCode}/status/report）
 *
 * <p>职责：
 * <ol>
 *   <li>同步维护 Redis 在线态（Key TTL + 在线集合），供离线判定使用</li>
 *   <li>若 DB 上次非 ONLINE，同步提升 status + lastOnlineTime（已 ONLINE 则跳过，节流防刷库）</li>
 * </ol>
 *
 * <p>由 {@link org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher} 投递到
 * {@code keepaliveExecutor} 执行，DB 写为同步调用以保证未激活设备能及时变为在线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusHandler {

    private static final long PRESENCE_TTL_MINUTES =
            DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1;

    private static final String PRESENCE_PLACEHOLDER = "{\"keepalive\":true}";

    private final StringRedisTemplate redisTemplate;
    private final DeviceStatusPersistenceService persistenceService;
    private final DeviceDbStatusCache dbStatusCache;

    /** DB 非 ONLINE→在线 同步写库节流（毫秒），避免 keepalive 风暴重复查库 */
    @Value("${mqtt.status-offline-promote-throttle-ms:60000}")
    private long offlinePromoteThrottleMs;

    private final ConcurrentHashMap<String, Long> offlinePromoteLastAt = new ConcurrentHashMap<>();

    /**
     * 刷新设备在线态：Key 不存在则创建并设 TTL，存在则续期；同时加入在线集合。
     */
    public void refreshPresence(String deviceCode, String payload) {
        log.info("[StatusHandler] [{}]设备上报状态信息：{}, 刷新设备在线态", deviceCode, payload);
        try {
            touchPresence(deviceCode,payload);
        } catch (Exception e) {
            log.warn("[StatusHandler] Redis presence 失败 deviceCode={}", deviceCode, e);
        }
        promoteOnlineIfNeeded(deviceCode);
    }

    /** 兼容旧调用名 */
    public void refreshKeepalivePresence(String deviceCode, String payload) {
        refreshPresence(deviceCode, payload);
    }

    private void touchPresence(String deviceCode, String payload) {
        String statusKey = DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode;
        long ttlSeconds = PRESENCE_TTL_MINUTES * 60;
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            conn.setEx(statusKey, ttlSeconds, payload);
            conn.sAdd(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            return null;
        });
    }

    /**
     * DB 已 ONLINE 则跳过；否则节流后同步 {@link DeviceStatusPersistenceService#promoteOnlineIfOfflineSync}。
     */
    private void promoteOnlineIfNeeded(String deviceCode) {
        if (dbStatusCache.isOnline(deviceCode)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastAt = offlinePromoteLastAt.get(deviceCode);
        if (lastAt != null && now - lastAt < offlinePromoteThrottleMs) {
            return;
        }
        offlinePromoteLastAt.put(deviceCode, now);
        persistenceService.promoteOnlineIfOfflineSync(deviceCode);
    }
}
