package org.jeecg.modules.commhub.service;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Webhook 出站 HTTP 推送：HMAC-SHA256 签名。
 *
 * <p>本类只执行单次 HTTP 推送，不再在异步线程内循环重试或 Thread.sleep；
 * 重试次数、退避时间与最终失败状态由 {@code WebhookDeliveryWorker} 更新投递任务维护。
 */
@Component
public class WebhookDispatchClient {

    private static final String SIGNATURE_HEADER = "X-Webhook-Signature";
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
