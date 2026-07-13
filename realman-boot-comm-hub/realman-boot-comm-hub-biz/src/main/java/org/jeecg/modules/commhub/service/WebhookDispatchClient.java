package org.jeecg.modules.commhub.service;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.commhub.vo.WebhookDispatchResult;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Webhook 出站 HTTP 单次推送客户端。
 *
 * <p>可靠重试不再在异步线程内 sleep 循环，而是由 {@code webhook_delivery_task}
 * 持久化任务和 {@code WebhookDeliveryWorker} 统一编排，保证服务重启后可恢复、失败可审计。
 */
@Component
public class WebhookDispatchClient {

    private static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    private static final int MAX_ERROR_LEN = 500;

    private final RestTemplate restTemplate;

    public WebhookDispatchClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void dispatchOnce(String callbackUrl, String hmacSecret, String bodyJson) {
        String signature = new HMac(HmacAlgorithm.HmacSHA256, hmacSecret.getBytes(StandardCharsets.UTF_8)).digestHex(bodyJson);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SIGNATURE_HEADER, signature);
        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);

        restTemplate.postForEntity(callbackUrl, entity, Void.class);
    }

}
