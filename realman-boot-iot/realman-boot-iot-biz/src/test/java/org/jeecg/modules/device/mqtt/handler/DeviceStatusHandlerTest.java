package org.jeecg.modules.device.mqtt.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceStatusHandlerTest {

    private StringRedisTemplate redisTemplate;
    private DeviceStatusPersistenceService persistenceService;
    private DeviceDbStatusCache dbStatusCache;
    private ValueOperations<String, String> valueOps;
    private DeviceStatusHandler handler;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        persistenceService = Mockito.mock(DeviceStatusPersistenceService.class);
        dbStatusCache = new DeviceDbStatusCache();
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.executePipelined(Mockito.<RedisCallback<Object>>any()))
                .thenReturn(Collections.emptyList());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);

        handler = new DeviceStatusHandler(redisTemplate, persistenceService, dbStatusCache);
        ReflectionTestUtils.setField(handler, "offlinePromoteThrottleMs", 60_000L);
    }

    @Test
    @DisplayName("refreshPresence 刷新 Redis")
    void refreshPresenceUpdatesRedis() {
        dbStatusCache.setStatus("DEV001", org.jeecg.modules.device.constant.DeviceConstant.DeviceStatus.ONLINE);

        handler.refreshPresence("DEV001", "{\"battery\":80}");

        verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
        verify(persistenceService, never()).promoteOnlineIfOffline(anyString());
    }

    @Test
    @DisplayName("DB 非 ONLINE 时 keepalive 触发软自愈")
    void refreshPresencePromotesDbWhenOffline() {
        when(persistenceService.promoteOnlineIfOffline("DEV001"))
                .thenReturn(DeviceStatusPersistenceService.PromoteOnlineResult.PROMOTED);

        handler.refreshPresence("DEV001", "{\"battery\":80}");

        verify(persistenceService).promoteOnlineIfOffline("DEV001");
    }
}
