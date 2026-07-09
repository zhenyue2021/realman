package org.jeecg.modules.commhub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * HTTP-MQTT 桥接下行发布按第三方（API Key）维度限流，固定窗口 {@code INCR}+{@code EXPIRE}，
 * 对齐设备通信中台详细设计 4.5"限流与幂等"。Redis 不可用时保守放行（不阻塞桥接主流程），
 * 与 {@code device-mgmt} 的 {@code DeviceRateLimitService} 是同一模式的独立实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BridgeRateLimitService {

    private static final String KEY_PREFIX = "comm-hub:bridge-rate-limit:";

    private final StringRedisTemplate redisTemplate;

    /** 同一 apiKeyId 每分钟超过 limitPerMinute 次时返回 true（调用方应拒绝本次请求）。 */
    public boolean isExceeded(String apiKeyId, int limitPerMinute) {
        try {
            String redisKey = KEY_PREFIX + apiKeyId;
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofMinutes(1));
            }
            return count != null && count > limitPerMinute;
        } catch (Exception e) {
            log.warn("[comm-hub] 桥接限流检查失败，保守放行 apiKeyId={}: {}", apiKeyId, e.getMessage());
            return false;
        }
    }
}
