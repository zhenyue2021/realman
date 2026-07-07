package org.jeecg.modules.device.datacollect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthResponseMsg;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 按设备缓存 OSS STS 临时凭证，减少重复向数采平台申请。
 *
 * <p>TTL 以 {@code utcExpiration} 为准，并预留安全缓冲时间，避免临近过期仍下发给设备。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class OssCredentialCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mqtt.collect-url-sts-cache-buffer-seconds:300}")
    private long bufferSeconds;

    @Value("${mqtt.collect-url-sts-cache-default-ttl-seconds:3600}")
    private long defaultTtlSeconds;

    @Value("${mqtt.collect-url-inflight-timeout-seconds:60}")
    private long inflightTimeoutSeconds;

    public Optional<CollectUrlResponseCmd.StsParams> getIfValid(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(credKey(deviceCode));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            CollectUrlResponseCmd.StsParams params =
                    objectMapper.readValue(json, CollectUrlResponseCmd.StsParams.class);
            if (!isCredentialValid(params)) {
                redisTemplate.delete(credKey(deviceCode));
                return Optional.empty();
            }
            return Optional.of(params);
        } catch (Exception e) {
            log.warn("[DataCollect] OSS 凭证缓存反序列化失败 deviceCode={}", deviceCode, e);
            redisTemplate.delete(credKey(deviceCode));
            return Optional.empty();
        }
    }

    public void put(String deviceCode, OssAuthResponseMsg.MsgData data) {
        if (deviceCode == null || deviceCode.isBlank() || data == null || !data.isSuccess()) {
            return;
        }
        CollectUrlResponseCmd.StsParams params = toStsParams(data);
        long ttlSeconds = computeTtlSeconds(params.getUtcExpiration());
        if (ttlSeconds <= 0) {
            log.debug("[DataCollect] OSS 凭证剩余有效期不足，跳过缓存 deviceCode={}", deviceCode);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(params);
            redisTemplate.opsForValue().set(credKey(deviceCode), json, ttlSeconds, TimeUnit.SECONDS);
            log.info("[DataCollect] OSS 凭证已缓存 deviceCode={} ttl={}s", deviceCode, ttlSeconds);
        } catch (Exception e) {
            log.warn("[DataCollect] OSS 凭证缓存写入失败 deviceCode={}", deviceCode, e);
        }
    }

    public boolean tryAcquireInflight(String deviceCode, String requestId) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return false;
        }
        long ttlSeconds = Math.max(inflightTimeoutSeconds, 1L);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                inflightKey(deviceCode), requestId != null ? requestId : "1", ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseInflight(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return;
        }
        redisTemplate.delete(inflightKey(deviceCode));
    }

    public CollectUrlResponseCmd buildSuccessResponse(String deviceCode, String requestId,
                                                      CollectUrlResponseCmd.StsParams params) {
        return CollectUrlResponseCmd.builder()
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .deviceSn(deviceCode)
                .code(0)
                .message(null)
                .params(params)
                .build();
    }

    public static CollectUrlResponseCmd.StsParams toStsParams(OssAuthResponseMsg.MsgData data) {
        return CollectUrlResponseCmd.StsParams.builder()
                .endpoint(data.getEndpoint())
                .bucket(data.getBucket())
                .bjExpiration(data.getBjExpiration())
                .utcExpiration(data.getUtcExpiration())
                .accessKeyId(data.getAccessKeyId())
                .accessKeySecret(data.getAccessKeySecret())
                .securityToken(data.getSecurityToken())
                .build();
    }

    private boolean isCredentialValid(CollectUrlResponseCmd.StsParams params) {
        if (params == null || params.getAccessKeyId() == null || params.getAccessKeyId().isBlank()
                || params.getEndpoint() == null || params.getEndpoint().isBlank()) {
            return false;
        }
        return computeTtlSeconds(params.getUtcExpiration()) > 0;
    }

    private long computeTtlSeconds(String utcExpiration) {
        if (utcExpiration == null || utcExpiration.isBlank()) {
            return defaultTtlSeconds;
        }
        try {
            long remaining = Instant.parse(utcExpiration).getEpochSecond()
                    - Instant.now().getEpochSecond()
                    - bufferSeconds;
            return remaining > 0 ? remaining : 0;
        } catch (Exception e) {
            log.warn("[DataCollect] utcExpiration 解析失败，使用默认 TTL: {}", utcExpiration);
            return defaultTtlSeconds;
        }
    }

    private static String credKey(String deviceCode) {
        return DataCollectConstant.REDIS_OSS_CRED_PREFIX + deviceCode;
    }

    private static String inflightKey(String deviceCode) {
        return DataCollectConstant.REDIS_OSS_INFLIGHT_PREFIX + deviceCode;
    }
}
