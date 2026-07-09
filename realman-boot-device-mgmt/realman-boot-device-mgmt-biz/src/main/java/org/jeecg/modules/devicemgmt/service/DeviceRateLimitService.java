package org.jeecg.modules.devicemgmt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 固定窗口限流（Redis {@code INCR}+{@code EXPIRE}），用于注册/凭证生成等防刷场景，
 * 对应 OTA 平台详细设计第七章标注的设备基座待补差距（{@code ERR_REGISTER_RATE_LIMIT}
 * /{@code ERR_SECRET_GENERATE_RATE_LIMIT}）。
 *
 * <p>Redis 不可用时保守放行（不阻塞设备注册这一关键路径），仅记 warn 日志，与
 * {@link DeviceSecretCacheService} 对 Redis 故障的处理方式一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceRateLimitService {

    private static final String KEY_PREFIX = "device-mgmt:rate-limit:";

    private final StringRedisTemplate redisTemplate;

    /** 同一 scope+key 组合每小时超过 limitPerHour 次时返回 true（调用方应拒绝本次请求）。 */
    public boolean isExceeded(String scope, String key, int limitPerHour) {
        try {
            String redisKey = KEY_PREFIX + scope + ":" + key;
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofHours(1));
            }
            return count != null && count > limitPerHour;
        } catch (Exception e) {
            log.warn("[device-mgmt] 限流检查失败，保守放行 scope={} key={}: {}", scope, key, e.getMessage());
            return false;
        }
    }
}
