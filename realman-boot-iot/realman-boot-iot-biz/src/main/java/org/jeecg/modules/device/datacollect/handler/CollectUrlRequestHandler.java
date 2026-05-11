package org.jeecg.modules.device.datacollect.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlRequestMsg;
import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * MQTT 上行处理：机器人请求 OSS 上传授权（device/{code}/datacollect/collectUrlRequest）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解析消息体，校验 requestId 非空</li>
 *   <li>调用 {@link OssAuthRequestProducer#sendAndStore} 存 Redis 映射并转发给数采平台</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${mqtt.enabled:false} && ${darwin.integration.enabled:false}")
public class CollectUrlRequestHandler {

    private final OssAuthRequestProducer ossAuthRequestProducer;
    private final ObjectMapper objectMapper;

    public void handle(String deviceCode, String payload) {
        CollectUrlRequestMsg msg;
        try {
            msg = objectMapper.readValue(payload, CollectUrlRequestMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] collectUrlRequest 反序列化失败 deviceCode={} payload={}",
                    deviceCode, payload, e);
            return;
        }

        if (msg.getRequestId() == null || msg.getRequestId().isBlank()) {
            log.warn("[DataCollect] collectUrlRequest 缺少 requestId deviceCode={}", deviceCode);
            return;
        }

        try {
            ossAuthRequestProducer.sendAndStore(msg.getRequestId(), deviceCode, null, MDC.get("traceId"));
        } catch (Exception e) {
            // Producer 内部已记录详细错误日志，此处仅记录上下文
            log.error("[DataCollect] OSS授权请求转发失败 requestId={} deviceCode={}",
                    msg.getRequestId(), deviceCode, e);
        }
    }
}
