package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 设备 DB 状态 Redis 缓存：keepalive 路径快速判断「DB 是否已 ONLINE」，多 Pod 共享。
 * 离线事件（$SYS / 定时任务）需同步 {@link #setStatus} 或 {@link #clear}。
 */
@Component
@RequiredArgsConstructor
public class DeviceDbStatusCache {

    private static final long CACHE_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;

    public boolean isOnline(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(cacheKey(deviceCode));
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(value.trim()) == DeviceConstant.DeviceStatus.ONLINE;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public void setStatus(String deviceCode, int status) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(
                cacheKey(deviceCode),
                String.valueOf(status),
                CACHE_HOURS,
                TimeUnit.HOURS);
    }

    public void clear(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return;
        }
        redisTemplate.delete(cacheKey(deviceCode));
    }

    static String cacheKey(String deviceCode) {
        return DeviceConstant.RedisKey.DEVICE_DB_STATUS_PREFIX + deviceCode;
    }
}
