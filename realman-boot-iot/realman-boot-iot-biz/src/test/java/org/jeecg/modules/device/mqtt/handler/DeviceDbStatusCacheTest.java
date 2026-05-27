package org.jeecg.modules.device.mqtt.handler;

import org.jeecg.modules.device.constant.DeviceConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceDbStatusCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DeviceDbStatusCache cache;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache = new DeviceDbStatusCache(redisTemplate);
    }

    @Test
    @DisplayName("Redis 值为 ONLINE 时 isOnline 为 true")
    void isOnlineWhenRedisHasOnlineStatus() {
        when(valueOps.get(DeviceDbStatusCache.cacheKey("DEV001")))
                .thenReturn(String.valueOf(DeviceConstant.DeviceStatus.ONLINE));

        assertThat(cache.isOnline("DEV001")).isTrue();
    }

    @Test
    @DisplayName("Redis 值为 OFFLINE 或缺失时 isOnline 为 false")
    void isOnlineFalseWhenOfflineOrMissing() {
        when(valueOps.get(DeviceDbStatusCache.cacheKey("DEV001")))
                .thenReturn(String.valueOf(DeviceConstant.DeviceStatus.OFFLINE));
        assertThat(cache.isOnline("DEV001")).isFalse();

        when(valueOps.get(DeviceDbStatusCache.cacheKey("DEV002"))).thenReturn(null);
        assertThat(cache.isOnline("DEV002")).isFalse();
    }

    @Test
    @DisplayName("setStatus 写入 Redis 并设置 TTL")
    void setStatusWritesRedis() {
        cache.setStatus("DEV001", DeviceConstant.DeviceStatus.ONLINE);

        verify(valueOps).set(
                eq(DeviceDbStatusCache.cacheKey("DEV001")),
                eq(String.valueOf(DeviceConstant.DeviceStatus.ONLINE)),
                eq(24L),
                eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("clear 删除 Redis Key")
    void clearDeletesRedisKey() {
        cache.clear("DEV001");
        verify(redisTemplate).delete(DeviceDbStatusCache.cacheKey("DEV001"));
    }
}
