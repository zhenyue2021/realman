package org.jeecg.modules.device.geo;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * IP 行政区划解析结果 Redis 缓存，多 Pod 共享，按 provider + IP 去重。
 */
@Component
@RequiredArgsConstructor
public class IpGeoCache {

    private final StringRedisTemplate redisTemplate;

    @Value("${device.mqtt-auth.ip-geo.cache.enabled:true}")
    private boolean enabled;

    @Value("${device.mqtt-auth.ip-geo.cache.ttl-hours:24}")
    private long ttlHours;

    public String get(String provider, String normalizedIp) {
        if (!enabled || normalizedIp == null || normalizedIp.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(cacheKey(provider, normalizedIp));
    }

    public void put(String provider, String normalizedIp, String address) {
        if (!enabled || normalizedIp == null || normalizedIp.isBlank()) {
            return;
        }
        if (address == null || address.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(
                cacheKey(provider, normalizedIp),
                address,
                Math.max(ttlHours, 1L),
                TimeUnit.HOURS);
    }

    static String cacheKey(String provider, String normalizedIp) {
        String p = provider == null || provider.isBlank() ? "amap" : provider.trim().toLowerCase();
        return DeviceConstant.RedisKey.IP_GEO_PREFIX + p + ":" + normalizedIp.trim();
    }
}
