package org.jeecg.modules.commhub.service;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Webhook 出站 HTTP 推送：HMAC-SHA256 签名 + 最多 3 次尽力而为重试。
 * 签名请求头 {@code X-Webhook-Signature}，值为 {@code hex(HMAC-SHA256(secret, body))}，
 * 供订阅方按同样算法校验来源合法性，对齐设备通信中台详细设计 4.3.2。
 */
@Slf4j
@Component
public class WebhookDispatchClient {

    private static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    private static final int MAX_ATTEMPTS = 3;

    private final RestTemplate restTemplate;

    public WebhookDispatchClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Async("webhookDispatchExecutor")
    public void dispatchAsync(String callbackUrl, String hmacSecret, String bodyJson) {
        String signature = new HMac(HmacAlgorithm.HmacSHA256, hmacSecret.getBytes(StandardCharsets.UTF_8)).digestHex(bodyJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SIGNATURE_HEADER, signature);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restTemplate.postForEntity(callbackUrl, entity, Void.class);
                return;
            } catch (Exception e) {
                log.warn("[comm-hub] Webhook 推送失败 url={} attempt={}/{}: {}", callbackUrl, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(200L * attempt);
                }
            }
        }
        log.error("[comm-hub] Webhook 推送最终失败（已达最大重试次数）url={}", callbackUrl);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
