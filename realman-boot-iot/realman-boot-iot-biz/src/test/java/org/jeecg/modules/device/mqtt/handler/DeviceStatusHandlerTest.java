package org.jeecg.modules.device.mqtt.handler;

import org.jeecg.modules.device.constant.DeviceConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeviceStatusHandlerTest {

    private StringRedisTemplate redisTemplate;
    private DeviceStatusPersistenceService persistenceService;
    private DeviceDbStatusCache dbStatusCache;
    private DeviceStatusHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        persistenceService = Mockito.mock(DeviceStatusPersistenceService.class);
        dbStatusCache = new DeviceDbStatusCache();
        handler = new DeviceStatusHandler(redisTemplate, persistenceService, dbStatusCache);
        Field throttle = DeviceStatusHandler.class.getDeclaredField("offlinePromoteThrottleMs");
        throttle.setAccessible(true);
        throttle.set(handler, 60_000L);
        when(redisTemplate.executePipelined(Mockito.<RedisCallback<Object>>any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("refreshPresence 始终刷新 Redis")
    void refreshPresenceUpdatesRedis() {
        handler.refreshPresence("DEV001", "");
        verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
    }

    @Test
    @DisplayName("DB 缓存已 ONLINE 时不触发同步写库")
    void skipPromoteWhenDbCacheOnline() {
        dbStatusCache.setStatus("DEV001", DeviceConstant.DeviceStatus.ONLINE);
        handler.refreshPresence("DEV001", "");
        verify(persistenceService, never()).promoteOnlineIfOfflineSync(any());
    }

    @Test
    @DisplayName("DB 缓存非 ONLINE 时同步 promote，且节流重复提交")
    void promoteWhenNotOnlineWithThrottle() {
        handler.refreshPresence("DEV002", "");
        handler.refreshPresence("DEV002", "");
        verify(persistenceService, Mockito.times(1)).promoteOnlineIfOfflineSync("DEV002");
    }
}
