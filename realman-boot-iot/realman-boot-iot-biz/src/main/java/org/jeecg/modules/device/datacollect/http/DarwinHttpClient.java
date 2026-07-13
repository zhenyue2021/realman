package org.jeecg.modules.device.datacollect.http;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.config.DataCollectIntegrationProperties;
import org.jeecg.modules.device.datacollect.dto.mq.DeviceStatusMsg;
import org.jeecg.modules.device.datacollect.dto.mq.FileAddressReportMsg;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthRequestMsg;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthResponseMsg;
import org.jeecg.modules.device.datacollect.entity.DarwinHttpOutbox;
import org.jeecg.modules.device.datacollect.log.MqMessageLogService;
import org.jeecg.modules.device.datacollect.mapper.DarwinHttpOutboxMapper;
import org.jeecg.modules.device.entity.IotMqMessageLog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Darwin 数采平台 HTTP 直连客户端，替代 {@code daily_GLN_PLATFORM} RocketMQ 主题的三个
 * 出站方向（OSS 授权/文件地址上报/设备状态推送），仅在 {@code darwin.integration.http-enabled=true}
 * 时装配，见 {@link DataCollectIntegrationProperties#isHttpEnabled()}。工单创建（Darwin→我方）
 * 与文件上报结果（Darwin→我方）两个入站方向见 {@code DarwinIntegrationController}。
 *
 * <p><b>重要限制</b>：达尔文平台真实 HTTP 契约（路径/字段/鉴权方式）本仓库无法访问，以下路径
 * 与请求/响应字段直接复用现有 RocketMQ 消息体结构（{@link OssAuthRequestMsg} 等），按 V2 主
 * 设计文档第六章列出的假设契约实现，尚未与达尔文平台侧对接确认，正式启用 http-enabled 前
 * 需要联调核实真实路径/字段/鉴权方案并按需调整。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "darwin.integration", name = "http-enabled", havingValue = "true")
public class DarwinHttpClient {

    private static final String PATH_OSS_AUTH = "/internal/data-processing/oss-auth";
    private static final String PATH_FILE_REPORT = "/internal/data-processing/file-report";
    private static final String PATH_DEVICE_STATUS = "/internal/data-processing/device-status";
    private static final int MAX_FAIL_REASON_LEN = 500;
    private static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";
    private static final String HEADER_REQUEST_HASH = "X-Request-Hash";
    private static final String HEADER_EVENT_ID = "X-Event-Id";

    private final DataCollectIntegrationProperties properties;
    private final MqMessageLogService mqMessageLogService;
    private final ObjectMapper objectMapper;
    private final DarwinHttpOutboxMapper outboxMapper;
    private final RestTemplate restTemplate;

    public DarwinHttpClient(DataCollectIntegrationProperties properties,
                             MqMessageLogService mqMessageLogService,
                             ObjectMapper objectMapper,
                             DarwinHttpOutboxMapper outboxMapper,
                             RestTemplateBuilder builder) {
        this.properties = properties;
        this.mqMessageLogService = mqMessageLogService;
        this.objectMapper = objectMapper;
        this.outboxMapper = outboxMapper;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttp().getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getHttp().getReadTimeoutMs()))
                .build();
    }

    /** 同步申请 OSS STS 凭证（异步执行，不阻塞调用方），替代 OssAuthRequestProducer+OssAuthResponseConsumer 的异步往返。 */
    @Async("darwinHttpExecutor")
    public CompletableFuture<OssAuthResponseMsg> requestOssAuth(OssAuthRequestMsg request) {
        try {
            OssAuthResponseMsg response = post(PATH_OSS_AUTH, request, OssAuthResponseMsg.class, request.getDeviceCode());
            return CompletableFuture.completedFuture(response);
        } catch (Exception ignored) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 异步上报 OSS 文件地址，fire-and-forget，失败不影响主流程（与原 RocketMQ Producer 语义一致）。 */
    @Async("darwinHttpExecutor")
    public void reportFileAddress(FileAddressReportMsg request) {
        postWithOutbox(PATH_FILE_REPORT, request, Void.class, request.getDeviceCode());
    }

    /** 异步上报设备上下线状态，fire-and-forget。 */
    @Async("darwinHttpExecutor")
    public void reportDeviceStatus(DeviceStatusMsg request) {
        postWithOutbox(PATH_DEVICE_STATUS, request, Void.class, request.getDeviceCode());
    }

    public boolean replayOutbox(DarwinHttpOutbox outbox) {
        try {
            postRaw(outbox.getPath(), outbox.getRequestBody(), Void.class, outbox.getDeviceCode(), outbox.getId());
            return true;
        } catch (Exception e) {
            log.warn("[DataCollect][HTTP] Outbox 重放失败 id={} path={} deviceCode={}: {}",
                    outbox.getId(), outbox.getPath(), outbox.getDeviceCode(), e.getMessage());
            return false;
        }
    }

    private <T> T postWithOutbox(String path, Object body, Class<T> responseType, String deviceCode) {
        try {
            return post(path, body, responseType, deviceCode);
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T> T post(String path, Object body, Class<T> responseType, String deviceCode) {
        long start = System.currentTimeMillis();
        String url = properties.getHttp().getBaseUrl() + path;
        IotMqMessageLog record = new IotMqMessageLog()
                .setDirection(1)
                .setTopic("HTTP")
                .setTag(path)
                .setCallerClass("DarwinHttpClient");
        try {
            record.setMessageBody(objectMapper.writeValueAsString(body));
        } catch (Exception ignored) {
            // 日志记录失败不影响主流程
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            applyIdempotencyHeaders(headers, record.getMessageBody(), deviceCode, path, null);
            String apiKey = properties.getHttp().getApiKey();
            if (StringUtils.hasText(apiKey)) {
                headers.set(properties.getHttp().getApiKeyHeader(), apiKey);
            }
            ResponseEntity<T> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), responseType);
            record.setCostTime(System.currentTimeMillis() - start).setStatus(1);
            mqMessageLogService.asyncSave(record);
            return response.getBody();
        } catch (Exception e) {
            record.setCostTime(System.currentTimeMillis() - start).setStatus(2).setFailReason(truncate(e.getMessage()));
            mqMessageLogService.asyncSave(record);
            log.warn("[DataCollect][HTTP] 调用数采平台失败 path={} deviceCode={}: {}", path, deviceCode, e.getMessage());
            if (responseType == Void.class) {
                enqueueOutbox(path, record.getMessageBody(), deviceCode, e.getMessage());
            }
            throw new IllegalStateException(e);
        }
    }

    private <T> T postRaw(String path, String bodyJson, Class<T> responseType, String deviceCode, String eventId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applyIdempotencyHeaders(headers, bodyJson, deviceCode, path, eventId);
        String apiKey = properties.getHttp().getApiKey();
        if (StringUtils.hasText(apiKey)) {
            headers.set(properties.getHttp().getApiKeyHeader(), apiKey);
        }
        String url = properties.getHttp().getBaseUrl() + path;
        ResponseEntity<T> response = restTemplate.postForEntity(url, new HttpEntity<>(bodyJson, headers), responseType);
        return response.getBody();
    }

    private void enqueueOutbox(String path, String bodyJson, String deviceCode, String error) {
        DarwinHttpOutbox outbox = new DarwinHttpOutbox();
        outbox.setPath(path);
        outbox.setRequestBody(bodyJson);
        outbox.setRequestHash(bodyJson == null ? null : DigestUtil.sha256Hex(bodyJson));
        outbox.setBizKey(deviceCode + ":" + path + ":" + (outbox.getRequestHash() == null ? "" : outbox.getRequestHash()));
        outbox.setDeviceCode(deviceCode);
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setMaxAttempts(8);
        outbox.setNextRetryAt(LocalDateTime.now().plusSeconds(60));
        outbox.setLastError(truncate(error));
        outboxMapper.insert(outbox);
    }

    private void applyIdempotencyHeaders(HttpHeaders headers, String bodyJson, String deviceCode, String path, String eventId) {
        String requestHash = bodyJson == null ? "" : DigestUtil.sha256Hex(bodyJson);
        headers.set(HEADER_REQUEST_HASH, requestHash);
        headers.set(HEADER_IDEMPOTENCY_KEY, deviceCode + ":" + path + ":" + requestHash);
        if (StringUtils.hasText(eventId)) {
            headers.set(HEADER_EVENT_ID, eventId);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_FAIL_REASON_LEN ? s : s.substring(0, MAX_FAIL_REASON_LEN) + "...";
    }
}
