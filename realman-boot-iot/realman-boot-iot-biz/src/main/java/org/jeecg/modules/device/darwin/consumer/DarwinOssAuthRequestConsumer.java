package org.jeecg.modules.device.darwin.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jeecg.modules.device.darwin.config.DarwinProperties;
import org.jeecg.modules.device.darwin.constant.DarwinTopicConstant;
import org.jeecg.modules.device.darwin.dto.DarwinOssAuthRequestDTO;
import org.jeecg.modules.device.darwin.dto.DarwinOssAuthResponseDTO;
import org.jeecg.modules.device.darwin.producer.DarwinOssAuthResponseProducer;
import org.jeecg.modules.device.darwin.service.DarwinUploadTokenService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = DarwinTopicConstant.OSS_AUTH_REQUEST,
        consumerGroup = "${rocketmq.consumer.group:REALMAN_IOT_CONSUMER_GROUP}",
        selectorExpression = DarwinTopicConstant.TAG_REQUEST
)
public class DarwinOssAuthRequestConsumer implements RocketMQListener<String> {

    private final DarwinUploadTokenService tokenService;
    private final DarwinOssAuthResponseProducer responseProducer;
    private final DarwinProperties darwinProperties;
    private final ObjectMapper objectMapper;

    /** 本平台文件上传接口地址前缀，通过 Nacos 注入 */
    @Value("${darwin.integration.file-upload.upload-url-prefix:}")
    private String uploadUrlPrefix;

    @Override
    public void onMessage(String message) {
        DarwinOssAuthRequestDTO request;
        try {
            request = objectMapper.readValue(message, DarwinOssAuthRequestDTO.class);
        } catch (Exception e) {
            log.error("[Darwin] OSS 授权申请反序列化失败 payload={}", message, e);
            return;
        }

        if (request.getTraceId() != null) {
            MDC.put("traceId", request.getTraceId());
        }

        DarwinOssAuthResponseDTO response;
        try {
            validateRequest(request);

            String token = tokenService.generateToken(request);
            LocalDateTime expireAt = tokenService.tokenExpireAt();

            response = DarwinOssAuthResponseDTO.builder()
                    .traceId(request.getTraceId())
                    .correlationId(request.getCorrelationId())
                    .success(true)
                    .uploadUrl(uploadUrlPrefix + "/darwin/file/upload")
                    .uploadToken(token)
                    .tokenExpireAt(expireAt)
                    .errorCode("")
                    .errorMsg("")
                    .build();

            log.info("[Darwin] OSS 授权 Token 已生成 correlationId={} bizType={}",
                    request.getCorrelationId(), request.getBizType());
        } catch (IllegalArgumentException e) {
            // 业务校验失败：直接返回错误响应，不重试
            log.warn("[Darwin] OSS 授权申请校验失败 correlationId={} reason={}",
                    request.getCorrelationId(), e.getMessage());
            response = DarwinOssAuthResponseDTO.builder()
                    .traceId(request.getTraceId())
                    .correlationId(request.getCorrelationId())
                    .success(false)
                    .errorCode("INVALID_REQUEST")
                    .errorMsg(e.getMessage())
                    .build();
        } finally {
            MDC.remove("traceId");
        }

        try {
            responseProducer.send(response);
        } catch (Exception e) {
            log.error("[Darwin] OSS 授权响应发送失败 correlationId={}", request.getCorrelationId(), e);
            throw new RuntimeException("OSS 授权响应发送失败", e);
        }
    }

    private void validateRequest(DarwinOssAuthRequestDTO req) {
        DarwinProperties.FileUpload cfg = darwinProperties.getFileUpload();

        if (req.getBizType() == null || !cfg.getAllowedBizTypes().contains(req.getBizType())) {
            throw new IllegalArgumentException("不支持的 bizType: " + req.getBizType());
        }
        if (req.getMimeType() == null || !cfg.getAllowedMimeTypes().contains(req.getMimeType())) {
            throw new IllegalArgumentException("不支持的 mimeType: " + req.getMimeType());
        }
        long maxBytes = (long) cfg.getMaxFileSizeMb() * 1024 * 1024;
        if (req.getFileSize() > maxBytes) {
            throw new IllegalArgumentException("文件超过大小限制: " + req.getFileSize() + " > " + maxBytes);
        }
    }
}
