package org.jeecg.modules.device.datacollect.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthResponseMsg;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.jeecg.modules.device.datacollect.service.DataCollectCommandService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
        selectorExpression = DataCollectConstant.MQ_TAG_RESPONSE
)
public class OssAuthResponseConsumer implements RocketMQListener<String> {

    private final DataCollectCommandService commandService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        OssAuthResponseMsg resp;
        try {
            resp = objectMapper.readValue(message, OssAuthResponseMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] OSS授权响应反序列化失败 payload={}", message, e);
            return;
        }

        if (resp.getRequestId() == null || resp.getRequestId().isBlank()) {
            log.error("[DataCollect] OSS授权响应缺少 requestId");
            return;
        }

        if (resp.getTraceId() != null) {
            MDC.put("traceId", resp.getTraceId());
        }

        try {
            String redisKey = DataCollectConstant.REDIS_OSS_REQUEST_PREFIX + resp.getRequestId();
            String deviceCode = redisTemplate.opsForValue().get(redisKey);
            if (deviceCode == null) {
                log.warn("[DataCollect] 未找到 requestId 对应设备，可能已超时或重复处理 requestId={}",
                        resp.getRequestId());
                return;
            }
            // 原子消费，防止多节点重复下发
            redisTemplate.delete(redisKey);

            if (!resp.isSuccess()) {
                log.warn("[DataCollect] 数采平台 OSS 授权失败 requestId={} errorCode={} errorMsg={}",
                        resp.getRequestId(), resp.getErrorCode(), resp.getErrorMsg());
                // 回传失败响应，机器人可感知错误并及时释放等待，而非超时
                CollectUrlResponseCmd errCmd = CollectUrlResponseCmd.builder()
                        .requestId(resp.getRequestId())
                        .timestamp(System.currentTimeMillis())
                        .deviceSn(deviceCode)
                        .code(400)
                        .message(resp.getErrorMsg() != null ? resp.getErrorMsg() : "OSS授权失败")
                        .params(null)
                        .build();
                commandService.sendCollectUrlResponse(deviceCode, errCmd);
                log.info("[DataCollect] OSS授权失败响应已下发至机器人 requestId={} deviceCode={}",
                        resp.getRequestId(), deviceCode);
                return;
            }

            CollectUrlResponseCmd cmd = CollectUrlResponseCmd.builder()
                    .requestId(resp.getRequestId())
                    .timestamp(System.currentTimeMillis())
                    .deviceSn(deviceCode)
                    .code(0)
                    .message(null)
                    .params(CollectUrlResponseCmd.StsParams.builder()
                            .endpoint(resp.getEndpoint())
                            .bucket(resp.getBucket())
                            .bjExpiration(resp.getBjExpiration())
                            .utcExpiration(resp.getUtcExpiration())
                            .accessKeyId(resp.getAccessKeyId())
                            .accessKeySecret(resp.getAccessKeySecret())
                            .securityToken(resp.getSecurityToken())
                            .build())
                    .build();

            commandService.sendCollectUrlResponse(deviceCode, cmd);
            // accessKeySecret / securityToken 敏感，只打 requestId 和 deviceCode
            log.info("[DataCollect] STS凭证已下发至机器人 requestId={} deviceCode={}",
                    resp.getRequestId(), deviceCode);

        } catch (Exception e) {
            log.error("[DataCollect] 处理 OSS 授权响应失败 requestId={}", resp.getRequestId(), e);
            throw new RuntimeException(e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
