package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.MqSendHelper;
import org.jeecg.modules.device.datacollect.config.DataCollectIntegrationProperties;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthRequestMsg;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthResponseMsg;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.jeecg.modules.device.datacollect.http.DarwinHttpClient;
import org.jeecg.modules.device.datacollect.service.DataCollectCommandService;
import org.jeecg.modules.device.datacollect.service.OssCredentialCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 将机器人的 OSS 授权请求转发给数采平台（Teleop → Darwin）。
 *
 * <p>{@code darwin.integration.http-enabled=false}（默认）：走既有 RocketMQ 异步路径，
 * 在 Redis 中存入 requestId → deviceCode 映射（TTL 2h），供
 * {@link org.jeecg.modules.device.datacollect.consumer.OssAuthResponseConsumer}
 * 收到数采平台 STS 凭证后反查目标设备并通过 MQTT 下推。
 *
 * <p>{@code http-enabled=true}：改走 {@link DarwinHttpClient} 同步 HTTP 直连（异步执行，
 * 不阻塞调用方），HTTP 响应到达后直接在本类内完成原本由 Consumer 负责的下推逻辑
 * （凭证缓存/MQTT 下发/失败通知），因为 HTTP 把请求-响应折叠成一次往返，不再需要
 * Redis requestId → deviceCode 映射这一异步关联步骤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class OssAuthRequestProducer {

    private static final long REQUEST_TTL_HOURS = 2;

    private final MqSendHelper mqSendHelper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OssCredentialCacheService credentialCache;
    private final DataCollectIntegrationProperties properties;

    /** http-enabled=false 时不装配，见 {@link DarwinHttpClient} 的 {@code @ConditionalOnProperty}。 */
    @Autowired(required = false)
    private DarwinHttpClient darwinHttpClient;

    /** MQTT 未启用时此 Bean 不存在，HTTP 路径下收到响应后无法下发时跳过 MQTT 通知步骤。 */
    @Autowired(required = false)
    private DataCollectCommandService commandService;

    public void sendAndStore(String requestId, String tenant, String deviceCode,
                             String taskId, String traceId) {
        if (properties.isHttpEnabled()) {
            sendViaHttp(requestId, tenant, deviceCode, taskId, traceId);
            return;
        }
        sendViaMq(requestId, tenant, deviceCode, taskId, traceId);
    }

    private void sendViaHttp(String requestId, String tenant, String deviceCode, String taskId, String traceId) {
        if (darwinHttpClient == null) {
            log.warn("[DataCollect][HTTP] DarwinHttpClient 未装配，跳过 OSS 授权请求 requestId={} deviceCode={}", requestId, deviceCode);
            credentialCache.releaseInflight(deviceCode);
            return;
        }
        OssAuthRequestMsg request = OssAuthRequestMsg.builder()
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

        darwinHttpClient.requestOssAuth(request).whenComplete((response, ex) -> {
            credentialCache.releaseInflight(deviceCode);
            if (ex != null) {
                log.error("[DataCollect][HTTP] OSS授权请求调用异常 requestId={} deviceCode={}", requestId, deviceCode, ex);
                notifyFailure(deviceCode, requestId, ex.getMessage());
                return;
            }
            OssAuthResponseMsg.MsgData data = response != null ? response.getData() : null;
            if (data == null || !data.isSuccess()) {
                String errMsg = data != null && data.getErrorMsg() != null ? data.getErrorMsg() : "数采平台无响应或授权失败";
                log.warn("[DataCollect][HTTP] 数采平台 OSS 授权失败 requestId={} deviceCode={} reason={}",
                        requestId, deviceCode, errMsg);
                notifyFailure(deviceCode, requestId, errMsg);
                return;
            }
            credentialCache.put(deviceCode, data);
            CollectUrlResponseCmd.StsParams stsParams = OssCredentialCacheService.toStsParams(data);
            CollectUrlResponseCmd cmd = credentialCache.buildSuccessResponse(deviceCode, requestId, stsParams);
            if (commandService != null) {
                commandService.sendCollectUrlResponse(deviceCode, cmd);
                log.info("[DataCollect][HTTP] STS凭证已下发至机器人 requestId={} deviceCode={}", requestId, deviceCode);
            } else {
                log.warn("[DataCollect][HTTP] MQTT未启用，跳过STS凭证MQTT下发 requestId={}", requestId);
            }
        });
    }

    private void notifyFailure(String deviceCode, String requestId, String errorMessage) {
        if (commandService == null) {
            log.warn("[DataCollect][HTTP] MQTT未启用，无法下发 OSS 失败通知 requestId={} deviceCode={}", requestId, deviceCode);
            return;
        }
        try {
            commandService.sendCollectUrlFailure(deviceCode, requestId, errorMessage);
        } catch (Exception e) {
            log.warn("[DataCollect][HTTP] OSS 失败通知下发异常 requestId={} deviceCode={}", requestId, deviceCode, e);
        }
    }

    private void sendViaMq(String requestId, String tenant, String deviceCode, String taskId, String traceId) {
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
            mqSendHelper.asyncSend(destination, springMessage, getClass().getSimpleName(), (receipt, ex) -> {
                if (ex != null) {
                    redisTemplate.delete(redisKey);
                    credentialCache.releaseInflight(deviceCode);
                    log.error("[DataCollect] OSS授权请求转发失败 requestId={} deviceCode={}",
                            requestId, deviceCode, ex);
                } else {
                    log.info("[DataCollect] OSS授权请求已转发 requestId={} deviceCode={} msgId={}",
                            requestId, deviceCode, receipt.getMessageId());
                }
            });
        } catch (Exception e) {
            redisTemplate.delete(redisKey);
            credentialCache.releaseInflight(deviceCode);
            log.error("[DataCollect] OSS授权请求序列化失败 requestId={} deviceCode={}",
                    requestId, deviceCode, e);
            mqSendHelper.logSendFailure(destination, null, getClass().getSimpleName(), traceId, e);
        }
    }
}
