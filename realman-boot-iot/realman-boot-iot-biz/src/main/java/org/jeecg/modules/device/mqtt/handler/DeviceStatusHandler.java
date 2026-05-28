package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 设备 keepalive / status/report 处理器（Topic: device/{deviceCode}/status/report）
 *
 * <p>职责：
 * <ol>
 *   <li>刷新 Redis presence（Key TTL + 在线集合），供离线判定与主控登录查询</li>
 *   <li>保活软自愈：DB 因平台重启/Redis 过期被误判离线时，在收到 keepalive 后恢复 DB ONLINE</li>
 * </ol>
 *
 * <p>权威上下线仍由 {@link DeviceOnlineOfflineHandler}（$SYS）负责；本 Handler 仅在对账/自愈场景写 DB。
 *
 * @see DeviceOnlineOfflineHandler $SYS connected/disconnected 为首次上线与真实断线的主路径
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusHandler {

    private static final long PRESENCE_TTL_MINUTES =
            DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1;

    private final StringRedisTemplate redisTemplate;
    private final DeviceStatusPersistenceService persistenceService;
    private final DeviceDbStatusCache dbStatusCache;

    /** keepalive 触发 DB 离线→在线 的最小间隔（毫秒），避免高频 keepalive 打 DB */
    @Value("${mqtt.status-offline-promote-throttle-ms:60000}")
    private long offlinePromoteThrottleMs;

    /**
     * 刷新设备 Redis 在线 presence，并在 DB 非 ONLINE 时尝试软自愈。
     */
    public void refreshPresence(String deviceCode, String payload) {
        log.info("[StatusHandler] [{}] keepalive 刷新 Redis presence, {}", deviceCode, payload);
        try {
            touchPresence(deviceCode, payload);
            healDbIfNeeded(deviceCode);
        } catch (Exception e) {
            log.warn("[StatusHandler] Redis presence 失败 deviceCode={}", deviceCode, e);
        }
    }

    /** 兼容旧调用名 */
    public void refreshKeepalivePresence(String deviceCode, String payload) {
        refreshPresence(deviceCode, payload);
    }

    private void healDbIfNeeded(String deviceCode) {
        if (dbStatusCache.isOnline(deviceCode)) {
            return;
        }
        if (!tryAcquirePromoteThrottle(deviceCode)) {
            log.info("[StatusHandler] [{}] keepalive DB 自愈节流中", deviceCode);
            return;
        }
        DeviceStatusPersistenceService.PromoteOnlineResult result =
                persistenceService.promoteOnlineIfOffline(deviceCode);
        if (result == DeviceStatusPersistenceService.PromoteOnlineResult.PROMOTED) {
            log.info("[StatusHandler] [{}] keepalive 软自愈：DB 已恢复 ONLINE", deviceCode);
        }
    }

    private boolean tryAcquirePromoteThrottle(String deviceCode) {
        String key = DeviceConstant.RedisKey.KEEPALIVE_PROMOTE_THROTTLE_PREFIX + deviceCode;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, "1", offlinePromoteThrottleMs, TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void touchPresence(String deviceCode, String payload) {
        String statusKey = DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode;
        long ttlSeconds = PRESENCE_TTL_MINUTES * 60;
        String cachePayload = (payload != null && !payload.isBlank()) ? payload : "{\"keepalive\":true}";
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            conn.setEx(statusKey, ttlSeconds, cachePayload);
            conn.sAdd(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            return null;
        });
    }
}
