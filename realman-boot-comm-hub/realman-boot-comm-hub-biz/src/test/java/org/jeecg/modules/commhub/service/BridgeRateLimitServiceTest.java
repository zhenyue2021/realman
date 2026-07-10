package org.jeecg.modules.commhub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP-MQTT 桥接按 API Key 维度限流，与 device-mgmt 的 DeviceRateLimitService
 * 是同一固定窗口模式的独立实现，覆盖点相同：边界判定 + Redis 故障保守放行。
 */
@ExtendWith(MockitoExtension.class)
class BridgeRateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private BridgeRateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new BridgeRateLimitService(redisTemplate);
    }

    @Test
    void isExceeded_shouldReturnFalseWhenAtLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(60L);

        assertThat(rateLimitService.isExceeded("key-1", 60)).isFalse();
    }

    @Test
    void isExceeded_shouldReturnTrueWhenOverLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(61L);

        assertThat(rateLimitService.isExceeded("key-1", 60)).isTrue();
    }

    @Test
    void isExceeded_shouldSetOneMinuteExpiryOnlyOnFirstIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        rateLimitService.isExceeded("key-1", 60);

        verify(redisTemplate).expire(anyString(), eq(Duration.ofMinutes(1)));
    }

    @Test
    void isExceeded_shouldNotResetExpiryOnSubsequentIncrements() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(2L);

        rateLimitService.isExceeded("key-1", 60);

        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void isExceeded_shouldFailOpenWhenRedisThrows() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThat(rateLimitService.isExceeded("key-1", 60)).isFalse();
    }
}
