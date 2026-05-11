package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthRequestMsg;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 将机器人的 OSS 授权请求转发给数采平台（Teleop → Darwin）。
 *
 * <p>同时在 Redis 中存入 requestId → deviceCode 映射（TTL 2h），
 * 供 {@link org.jeecg.modules.device.datacollect.consumer.OssAuthResponseConsumer}
 * 收到数采平台 STS 凭证后反查目标设备并通过 MQTT 下推。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssAuthRequestProducer {

    private static final long REQUEST_TTL_HOURS = 2;

    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void sendAndStore(String requestId, String tenant, String deviceCode,
                             String taskId, String traceId) {
        // 先写 Redis，再发 MQ，确保响应到来时一定能查到映射
        String redisKey = DataCollectConstant.REDIS_OSS_REQUEST_PREFIX + requestId;
        redisTemplate.opsForValue().set(redisKey, deviceCode, REQUEST_TTL_HOURS, TimeUnit.HOURS);

        OssAuthRequestMsg msg = OssAuthRequestMsg.builder()
                .tenant(tenant)
                .deviceCode(deviceCode)
                .traceId(traceId)
                .eventTime(System.currentTimeMillis())
                .data(OssAuthRequestMsg.MsgData.builder()
                        .requestId(requestId)
                        .taskId(taskId != null ? taskId : "")
                        .build())
                .build();

        String destination = DataCollectConstant.MQ_TOPIC_OSS_AUTH_REQUEST
                + ":" + DataCollectConstant.MQ_TAG_REQUEST;
        try {
            rocketMQTemplate.send(destination,
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(msg)).build());
            log.info("[DataCollect] OSS授权请求已转发 requestId={} deviceCode={}", requestId, deviceCode);
        } catch (Exception e) {
            // 发送失败清理 Redis，避免悬挂 key
            redisTemplate.delete(redisKey);
            log.error("[DataCollect] OSS授权请求转发失败 requestId={} deviceCode={}", requestId, deviceCode, e);
            throw new RuntimeException("OSS授权请求转发失败", e);
        }
    }
}
