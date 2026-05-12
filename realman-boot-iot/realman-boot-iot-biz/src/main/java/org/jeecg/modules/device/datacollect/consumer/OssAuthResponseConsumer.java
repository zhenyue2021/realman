package org.jeecg.modules.device.datacollect.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthResponseMsg;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.jeecg.modules.device.datacollect.service.DataCollectCommandService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Darwin → Teleop（RocketMQ）：接收数采平台返回的 OSS STS 凭证，
 * 通过 Redis 反查目标设备，再经 MQTT 下推给机器人。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = DataCollectConstant.MQ_TOPIC_OSS_AUTH_RESPONSE,
        consumerGroup = DataCollectConstant.MQ_GROUP_OSS_AUTH_RESPONSE,
        tag = DataCollectConstant.MQ_TAG_RESPONSE,
        namespace = "${rocketmq.push-consumer.namespace:}"
)
public class OssAuthResponseConsumer implements RocketMQListener {

    /** MQTT 未启用时此 Bean 不存在，消费消息时跳过 MQTT 下发步骤 */
    @Autowired(required = false)
    private DataCollectCommandService commandService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String message = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();

        OssAuthResponseMsg resp;
        try {
            resp = objectMapper.readValue(message, OssAuthResponseMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] OSS授权响应反序列化失败 payload={}", message, e);
            return ConsumeResult.SUCCESS;
        }

        OssAuthResponseMsg.MsgData data = resp.getData();
        if (data == null || data.getRequestId() == null || data.getRequestId().isBlank()) {
            log.error("[DataCollect] OSS授权响应缺少 data 或 requestId");
            return ConsumeResult.SUCCESS;
        }

        if (resp.getTraceId() != null) {
            MDC.put("traceId", resp.getTraceId());
        }

        try {
            String redisKey = DataCollectConstant.REDIS_OSS_REQUEST_PREFIX + data.getRequestId();
            String deviceCode = redisTemplate.opsForValue().get(redisKey);
            if (deviceCode == null) {
                log.warn("[DataCollect] 未找到 requestId 对应设备，可能已超时或重复处理 requestId={}",
                        data.getRequestId());
                return ConsumeResult.SUCCESS;
            }
            // 原子消费，防止多节点重复下发
            redisTemplate.delete(redisKey);

            if (!data.isSuccess()) {
                log.warn("[DataCollect] 数采平台 OSS 授权失败 requestId={} errorCode={} errorMsg={}",
                        data.getRequestId(), data.getErrorCode(), data.getErrorMsg());
                if (commandService != null) {
                    CollectUrlResponseCmd errCmd = CollectUrlResponseCmd.builder()
                            .requestId(data.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .deviceSn(deviceCode)
                            .code(400)
                            .message(data.getErrorMsg() != null ? data.getErrorMsg() : "OSS授权失败")
                            .params(null)
                            .build();
                    commandService.sendCollectUrlResponse(deviceCode, errCmd);
                    log.info("[DataCollect] OSS授权失败响应已下发至机器人 requestId={} deviceCode={}",
                            data.getRequestId(), deviceCode);
                } else {
                    log.warn("[DataCollect] MQTT未启用，跳过OSS授权失败响应下发 requestId={}", data.getRequestId());
                }
                return ConsumeResult.SUCCESS;
            }

            CollectUrlResponseCmd cmd = CollectUrlResponseCmd.builder()
                    .requestId(data.getRequestId())
                    .timestamp(System.currentTimeMillis())
                    .deviceSn(deviceCode)
                    .code(0)
                    .message(null)
                    .params(CollectUrlResponseCmd.StsParams.builder()
                            .endpoint(data.getEndpoint())
                            .bucket(data.getBucket())
                            .bjExpiration(data.getBjExpiration())
                            .utcExpiration(data.getUtcExpiration())
                            .accessKeyId(data.getAccessKeyId())
                            .accessKeySecret(data.getAccessKeySecret())
                            .securityToken(data.getSecurityToken())
                            .build())
                    .build();

            if (commandService != null) {
                commandService.sendCollectUrlResponse(deviceCode, cmd);
                log.info("[DataCollect] STS凭证已下发至机器人 requestId={} deviceCode={}",
                        data.getRequestId(), deviceCode);
            } else {
                log.warn("[DataCollect] MQTT未启用，跳过STS凭证MQTT下发 requestId={}", data.getRequestId());
            }
            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("[DataCollect] 处理 OSS 授权响应失败 requestId={}", data.getRequestId(), e);
            return ConsumeResult.FAILURE;
        } finally {
            MDC.remove("traceId");
        }
    }
}
