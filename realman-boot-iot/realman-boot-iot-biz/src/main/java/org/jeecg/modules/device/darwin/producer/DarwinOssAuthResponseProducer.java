package org.jeecg.modules.device.darwin.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.jeecg.modules.device.darwin.constant.DarwinTopicConstant;
import org.jeecg.modules.device.darwin.dto.DarwinOssAuthResponseDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class DarwinOssAuthResponseProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public void send(DarwinOssAuthResponseDTO response) {
        String destination = DarwinTopicConstant.OSS_AUTH_RESPONSE + ":" + DarwinTopicConstant.TAG_RESPONSE;
        try {
            rocketMQTemplate.send(destination,
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(response)).build());
            log.info("[Darwin] OSS 授权响应已发送 correlationId={} success={}",
                    response.getCorrelationId(), response.isSuccess());
        } catch (Exception e) {
            log.error("[Darwin] OSS 授权响应发送失败 correlationId={}", response.getCorrelationId(), e);
            throw new RuntimeException("OSS 授权响应发送失败", e);
        }
    }
}
