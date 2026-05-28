package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 设备 keepalive / status/report 处理器（Topic: device/{deviceCode}/status/report）
 *
 * <p>职责：刷新 Redis presence（Key TTL + 在线集合），供离线判定与主控登录查询。
 *
 * <p>DB 上线、Darwin MQ 等副作用由 {@link DeviceOnlineOfflineHandler}（$SYS / mqtt-auth）统一处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusHandler {

    private static final long PRESENCE_TTL_MINUTES =
            DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1;

    private final StringRedisTemplate redisTemplate;

    public void refreshPresence(String deviceCode, String payload) {
        log.info("[StatusHandler] [{}] keepalive , {}", deviceCode, payload);
        try {
            touchPresence(deviceCode, payload);
        } catch (Exception e) {
            log.warn("[StatusHandler] Redis presence 失败 deviceCode={}", deviceCode, e);
        }
    }

    public void refreshKeepalivePresence(String deviceCode, String payload) {
        refreshPresence(deviceCode, payload);
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
