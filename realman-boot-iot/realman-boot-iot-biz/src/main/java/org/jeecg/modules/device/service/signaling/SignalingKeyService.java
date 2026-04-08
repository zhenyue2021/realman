package org.jeecg.modules.device.service.signaling;

import cn.hutool.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 信令服务器房间密钥管理
 *
 * <p>职责：
 * <ol>
 *   <li>生成 32 字节（64 位 Hex）的随机房间密钥</li>
 *   <li>通过 POST 接口推送给信令服务器</li>
 *   <li>推送成功后写入 Redis，以信令服务器地址为 Key，TTL=26h</li>
 * </ol>
 *
 * <p>配置示例（application.yml / Nacos）：
 * <pre>
 * signaling:
 *   server:
 *     url: http://192.168.1.100:8091
 * </pre>
 */
@Slf4j
@Service
public class SignalingKeyService {

    /**
     * 信令服务器根地址，例如 http://192.168.1.100:8091
     */
    @Value("${signaling.server.url:}")
    private String serverUrl;
    @Value("${signaling.server.port:}")
    private String serverPort;

    /**
     * 密钥推送接口路径
     */
    private static final String KEY_API_PATH = "/api/set_key";

    /**
     * Redis 中密钥的 TTL（比 24h 多 2h 作为缓冲，防止定时任务短暂延迟导致 Key 过期）
     */
    private static final long KEY_TTL_HOURS = 26L;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;

    public SignalingKeyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.restTemplate = buildRestTemplate();
    }

    /**
     * 生成新密钥并推送给信令服务器，成功后更新 Redis 缓存。
     *
     * <p>若信令服务器地址未配置（{@code signaling.server.url} 为空），则跳过并记录警告。
     * 若推送失败，Redis 中保留旧密钥，不做替换，确保信令服务器与缓存始终一致。
     */
    public void generateAndPush() {
        if (serverUrl == null || serverUrl.isBlank()) {
            log.warn("[Signaling] signaling.server.url 未配置，跳过密钥生成");
            return;
        }

        String newKey = generateKey();
        boolean pushed = pushToServer(newKey);
        if (pushed) {
            storeInRedis(newKey);
            log.info("[Signaling] 密钥已更新并推送至信令服务器 url={}", serverUrl);
        } else {
            log.warn("[Signaling] 密钥推送失败，Redis 保留旧密钥 url={}", serverUrl);
        }
    }

    /**
     * 从 Redis 查询当前有效密钥（供其他模块使用）。
     *
     * @return 当前密钥，未初始化或已过期时返回 null
     */
    public String getCurrentKey() {
        return redisTemplate.opsForValue().get(redisKey());
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    /**
     * 生成 32 字节（64 位 Hex）安全随机密钥
     */
    private static String generateKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }

    /**
     * 推送密钥至信令服务器，返回是否成功
     */
    private boolean pushToServer(String key) {
        String url = "http://" + serverUrl + ":" + serverPort + KEY_API_PATH;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                    Map.of("room_key", key), headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object success = response.getBody().get("success");
                if (Boolean.TRUE.equals(success)) {
                    return true;
                }
                log.warn("[Signaling] 推送响应 success=false url={} body={}", url, response.getBody());
            } else {
                log.warn("[Signaling] 推送响应非 2xx url={} status={}", url, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[Signaling] 推送密钥异常 url={}", url, e);
        }
        return false;
    }

    /**
     * 将密钥写入 Redis，Key 为信令服务器地址，TTL=26h
     */
    private void storeInRedis(String key) {
        redisTemplate.opsForValue().set(redisKey(), key, KEY_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * Redis Key：固定前缀 + 信令服务器地址
     */
    private String redisKey() {
        return DeviceConstant.RedisKey.SIGNALING_KEY_PREFIX + serverUrl;
    }

    /**
     * 构造带超时限制的 RestTemplate（避免信令服务器无响应时阻塞线程）
     */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

}
