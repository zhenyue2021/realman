package org.jeecg.modules.device.mqtt.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;

import static org.mockito.Mockito.when;

/**
 * DeviceStatusHandler 单元测试：仅 Redis 在线态续期。
 */
public class DeviceStatusHandlerTest {

    private StringRedisTemplate redisTemplate;
    private DeviceStatusHandler handler;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        handler = new DeviceStatusHandler(redisTemplate);
        when(redisTemplate.executePipelined(Mockito.<RedisCallback<Object>>any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void refreshPresenceUpdatesRedis() {
        handler.refreshPresence("DEV001", "");
        Mockito.verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
    }

    @Test
    void refreshKeepalivePresenceDelegatesToRefreshPresence() {
        handler.refreshKeepalivePresence("DEV001", "ignored");
        Mockito.verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
    }
}
