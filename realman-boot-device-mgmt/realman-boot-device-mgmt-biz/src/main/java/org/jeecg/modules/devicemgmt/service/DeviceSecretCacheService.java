package org.jeecg.modules.devicemgmt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.devicemgmt.vo.CachedDeviceSecret;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * MQTT 连接层密钥的 Redis 二级缓存，供 {@code validateSecret} 高频调用（每次 MQTT
 * CONNECT 都会触发）使用，避免每次都打到 {@code device_credential} 表。
 *
 * <p>TTL 5 分钟；provision（首次签发/重新注册）与 secret/reset（密钥重置）后主动
 * {@link #evict}，保证密钥变更后不会有旧密钥残留在缓存里造成短暂误放行/误拒绝。
 * 这与设备信息基础服务对 {@code online_status}/{@code occupancy_state} 等字段规划的
 * 只读缓存策略（见设备基座详细设计 2.3）是同一思路，各自独立实现，互不依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSecretCacheService {

    private static final String KEY_PREFIX = "device-mgmt:secret:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<CachedDeviceSecret> get(String deviceCode) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + deviceCode);
            if (!StringUtils.hasText(value)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, CachedDeviceSecret.class));
        } catch (Exception e) {
            log.warn("[device-mgmt] 密钥缓存读取失败，回退数据库查询 deviceCode={}: {}", deviceCode, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String deviceCode, CachedDeviceSecret cached) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + deviceCode, objectMapper.writeValueAsString(cached), TTL);
        } catch (Exception e) {
            log.warn("[device-mgmt] 密钥缓存写入失败（不影响本次校验结果）deviceCode={}: {}", deviceCode, e.getMessage());
        }
    }

    public void evict(String deviceCode) {
        try {
            redisTemplate.delete(KEY_PREFIX + deviceCode);
        } catch (Exception e) {
            log.warn("[device-mgmt] 密钥缓存失效失败 deviceCode={}: {}", deviceCode, e.getMessage());
        }
    }
}
