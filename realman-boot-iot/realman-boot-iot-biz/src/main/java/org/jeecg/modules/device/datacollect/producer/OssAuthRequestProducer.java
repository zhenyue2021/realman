package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthRequestMsg;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 将机器人的 OSS 授权请求转发给数采平台（Teleop → Darwin）。
 *
 * <p>同时在 Redis 中存入 requestId → deviceCode 映射（TTL 2h），
 * 供 {@link org.jeecg.modules.device.datacollect.consumer.OssAuthResponseConsumer}
 * 收到数采平台 STS 凭证后反查目标设备并通过 MQTT 下推。
 *
 * <p>MQ 发送为异步，不阻塞 MQTT 路由线程；发送失败时清理 Redis 映射。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssAuthRequestProducer {

    private static final long REQUEST_TTL_HOURS = 2;

    private final RocketMQClientTemplate rocketMQClientTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void sendAndStore(String requestId, String tenant, String deviceCode,
                             String taskId, String traceId) {
        String redisKey = DataCollectConstant.REDIS_OSS_REQUEST_PREFIX + requestId;
        redisTemplate.opsForValue().set(redisKey, deviceCode, REQUEST_TTL_HOURS, TimeUnit.HOURS);

        OssAuthRequestMsg msg = OssAuthRequestMsg.builder()
                .tenant(tenant)
                .deviceCode(deviceCode)
                .traceId(traceId)
                .requestId(requestId)
                .eventTime(System.currentTimeMillis())
                .data(OssAuthRequestMsg.MsgData.builder()
                        .requestId(requestId)
                        .taskId(taskId != null ? taskId : "")
                        .build())
                .build();

        String destination = DataCollectConstant.MQ_TOPIC_OSS_AUTH_REQUEST
                + ":" + DataCollectConstant.MQ_TAG_REQUEST;
        try {
            var springMessage = MessageBuilder.withPayload(objectMapper.writeValueAsString(msg))
                    .setHeader("deviceCode", deviceCode)
                    .build();
            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            CompletableFuture<SendReceipt> future = new CompletableFuture<>();
            rocketMQClientTemplate.asyncSendNormalMessage(destination, springMessage, future);
            future.whenComplete((receipt, ex) -> {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                try {
                    if (ex != null) {
                        redisTemplate.delete(redisKey);
                        log.error("[DataCollect] OSS授权请求转发失败 requestId={} deviceCode={}",
                                requestId, deviceCode, ex);
                    } else {
                        log.info("[DataCollect] OSS授权请求已转发 requestId={} deviceCode={} msgId={}",
                                requestId, deviceCode, receipt.getMessageId());
                    }
                } finally {
                    MDC.clear();
                }
            });
        } catch (Exception e) {
            redisTemplate.delete(redisKey);
            log.error("[DataCollect] OSS授权请求序列化失败 requestId={} deviceCode={}",
                    requestId, deviceCode, e);
        }
    }
}
