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
import java.util.concurrent.CompletableFuture;

/**
 * Webhook 出站 HTTP 推送：HMAC-SHA256 签名 + 尽力而为重试，退避间隔 1s/2s/5s/10s
 * （5 次尝试、4 次等待），对齐设备通信中台详细设计 4.3.2 的建议退避策略。
 * 签名请求头 {@code X-Webhook-Signature}，值为 {@code hex(HMAC-SHA256(secret, body))}，
 * 供订阅方按同样算法校验来源合法性。
 *
 * <p>返回 {@code CompletableFuture<Boolean>}（最终是否投递成功），供调用方据此更新
 * 订阅的连续失败计数并在达到阈值时自动暂停（见 {@code IWebhookSubscriptionService
 * #recordDispatchResult}），不在本类内直接依赖订阅服务，保持职责单一。
 */
@Slf4j
@Component
public class WebhookDispatchClient {

    private static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    /** 尝试之间的等待间隔（毫秒），长度 = 最大尝试次数 - 1。 */
    private static final long[] BACKOFF_MILLIS = {1000L, 2000L, 5000L, 10000L};

    private final RestTemplate restTemplate;

    public WebhookDispatchClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Async("webhookDispatchExecutor")
    public CompletableFuture<Boolean> dispatchAsync(String callbackUrl, String hmacSecret, String bodyJson) {
        String signature = new HMac(HmacAlgorithm.HmacSHA256, hmacSecret.getBytes(StandardCharsets.UTF_8)).digestHex(bodyJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SIGNATURE_HEADER, signature);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);

        int maxAttempts = BACKOFF_MILLIS.length + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restTemplate.postForEntity(callbackUrl, entity, Void.class);
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                log.warn("[comm-hub] Webhook 推送失败 url={} attempt={}/{}: {}", callbackUrl, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(BACKOFF_MILLIS[attempt - 1]);
                }
            }
        }
        log.error("[comm-hub] Webhook 推送最终失败（已达最大重试次数）url={}", callbackUrl);
        return CompletableFuture.completedFuture(false);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
