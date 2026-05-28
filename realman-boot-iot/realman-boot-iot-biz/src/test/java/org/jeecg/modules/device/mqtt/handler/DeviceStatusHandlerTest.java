package org.jeecg.modules.device.mqtt.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceStatusHandlerTest {

    private StringRedisTemplate redisTemplate;
    private DeviceStatusHandler handler;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.executePipelined(Mockito.<RedisCallback<Object>>any()))
                .thenReturn(Collections.emptyList());
        handler = new DeviceStatusHandler(redisTemplate);
    }

    @Test
    @DisplayName("refreshPresence 仅刷新 Redis")
    void refreshPresenceUpdatesRedisOnly() {
        handler.refreshPresence("DEV001", "{\"battery\":80}");
        verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
    }
}
