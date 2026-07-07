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
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.datacollect.service.DataCollectCommandService;
import org.jeecg.modules.device.datacollect.service.OssCredentialCacheService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.util.OperationLogDetail;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Darwin → Teleop（RocketMQ）：接收数采平台返回的 OSS STS 凭证，
 * 通过 Redis 反查目标设备，再经 MQTT 下推给机器人。
 *
 * <p>Poison 消息（反序列化失败、缺少必填字段）：记录 {@code [POISON_MESSAGE]} 后 SUCCESS 跳过，避免无限重试。
 * 业务失败：下发 MQTT code=1 失败响应，设备可感知后重试。
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
    private final IDeviceOperationLogService logService;
    private final OssCredentialCacheService credentialCache;

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String message = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();

        OssAuthResponseMsg resp;
        try {
            resp = objectMapper.readValue(message, OssAuthResponseMsg.class);
        } catch (Exception e) {
            log.error("[POISON_MESSAGE][DataCollect] OSS授权响应反序列化失败 messageId={} payload={}",
                    messageView.getMessageId(), message, e);
            return ConsumeResult.SUCCESS;
        }
        String requestId = resp.getRequestId();
        OssAuthResponseMsg.MsgData data = resp.getData();
        if (data == null || requestId == null || requestId.isBlank()) {
            log.error("[POISON_MESSAGE][DataCollect] OSS授权响应缺少 data 或 requestId messageId={} payload={}",
                    messageView.getMessageId(), message);
            return ConsumeResult.SUCCESS;
        }

        if (resp.getTraceId() != null) {
            MDC.put("traceId", resp.getTraceId());
        }

        try {
            String deviceCode = resp.getDeviceCode();
            String redisKey = DataCollectConstant.REDIS_OSS_REQUEST_PREFIX + requestId;
            if (deviceCode == null || deviceCode.isBlank()) {
                deviceCode = redisTemplate.opsForValue().get(redisKey);
            }
            redisTemplate.delete(redisKey);

            if (deviceCode == null || deviceCode.isBlank()) {
                log.warn("[DataCollect] 未找到 requestId 对应设备，可能已超时或重复处理 requestId={}",
                        requestId);
                logService.recordLog(null, null,
                        DeviceConstant.OperationType.DATA_COLLECT,
                        "OSS 授权响应无法关联设备",
                        OperationLogDetail.ofRequest(requestId, DataCollectConstant.MQ_TOPIC_OSS_AUTH_RESPONSE),
                        DeviceConstant.OperationSource.PLATFORM, "FAIL",
                        "requestId 无对应 deviceCode", null, null);
                return ConsumeResult.SUCCESS;
            }

            credentialCache.releaseInflight(deviceCode);

            if (!data.isSuccess()) {
                String errMsg = data.getErrorMsg() != null ? data.getErrorMsg()
                        : ("errorCode=" + data.getErrorCode());
                log.warn("[DataCollect] 数采平台 OSS 授权失败 requestId={} deviceCode={} errorCode={} errorMsg={}",
                        requestId, deviceCode, data.getErrorCode(), data.getErrorMsg());
                logService.recordLog(null, deviceCode,
                        DeviceConstant.OperationType.DATA_COLLECT,
                        "数采平台 OSS 授权失败",
                        OperationLogDetail.ofRequest(requestId,
                                "device/" + deviceCode + "/" + DataCollectConstant.MQTT_DOWN_COLLECT_URL_RESP),
                        DeviceConstant.OperationSource.PLATFORM, "FAIL", errMsg, null, null);
                notifyDeviceFailure(deviceCode, requestId, errMsg);
                return ConsumeResult.SUCCESS;
            }

            credentialCache.put(deviceCode, data);

            CollectUrlResponseCmd.StsParams stsParams = OssCredentialCacheService.toStsParams(data);
            CollectUrlResponseCmd cmd = credentialCache.buildSuccessResponse(deviceCode, requestId, stsParams);

            if (commandService != null) {
                commandService.sendCollectUrlResponse(deviceCode, cmd);
                log.info("[DataCollect] STS凭证已下发至机器人 requestId={} deviceCode={}",
                        requestId, deviceCode);
            } else {
                log.warn("[DataCollect] MQTT未启用，跳过STS凭证MQTT下发 requestId={}", requestId);
            }
            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("[DataCollect] 处理 OSS 授权响应失败 requestId={}", requestId, e);
            return ConsumeResult.FAILURE;
        } finally {
            MDC.remove("traceId");
        }
    }

    private void notifyDeviceFailure(String deviceCode, String requestId, String errorMessage) {
        if (commandService == null) {
            log.warn("[DataCollect] MQTT未启用，无法下发 OSS 失败通知 requestId={} deviceCode={}",
                    requestId, deviceCode);
            return;
        }
        try {
            commandService.sendCollectUrlFailure(deviceCode, requestId, errorMessage);
            log.info("[DataCollect] OSS 失败通知已下发 requestId={} deviceCode={}", requestId, deviceCode);
        } catch (Exception e) {
            log.warn("[DataCollect] OSS 失败通知下发异常 requestId={} deviceCode={}", requestId, deviceCode, e);
        }
    }
}
