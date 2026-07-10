package org.jeecg.modules.devicemgmt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册/凭证生成防刷限流，覆盖固定窗口计数的边界与 Redis 故障时"保守放行不阻塞
 * 设备注册关键路径"这一明确设计取舍（见类注释）。
 */
@ExtendWith(MockitoExtension.class)
class DeviceRateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DeviceRateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new DeviceRateLimitService(redisTemplate);
    }

    @Test
    void isExceeded_shouldReturnFalseWhenUnderLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(3L);

        boolean exceeded = rateLimitService.isExceeded("register", "device-001", 5);

        assertThat(exceeded).isFalse();
    }

    @Test
    void isExceeded_shouldReturnTrueWhenOverLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(6L);

        boolean exceeded = rateLimitService.isExceeded("register", "device-001", 5);

        assertThat(exceeded).isTrue();
    }

    @Test
    void isExceeded_shouldSetOneHourExpiryOnlyOnFirstIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        rateLimitService.isExceeded("register", "device-001", 5);

        verify(redisTemplate).expire(anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofHours(1)));
    }

    @Test
    void isExceeded_shouldNotSetExpiryWhenCounterAlreadyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(2L);

        rateLimitService.isExceeded("register", "device-001", 5);

        verify(redisTemplate, org.mockito.Mockito.never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void isExceeded_shouldFailOpenWhenRedisThrows() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        boolean exceeded = rateLimitService.isExceeded("register", "device-001", 5);

        assertThat(exceeded).isFalse();
    }
}
