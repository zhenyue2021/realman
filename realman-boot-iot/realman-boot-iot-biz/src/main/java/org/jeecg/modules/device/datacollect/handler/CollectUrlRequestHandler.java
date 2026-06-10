package org.jeecg.modules.device.datacollect.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlRequestMsg;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.jeecg.modules.device.datacollect.service.DeviceTenantResolver;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.util.OperationLogDetail;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MQTT 上行处理：机器人请求 OSS 上传授权（device/{code}/datacollect/collectUrlRequest）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解析消息体，校验 requestId 非空</li>
 *   <li>Redis requestId / deviceCode 去重与节流（集群安全，配合 $share 订阅）</li>
 *   <li>查询设备获取 tenantId（本地缓存，减少 DB 压力）</li>
 *   <li>调用 {@link OssAuthRequestProducer#sendAndStore} 存 Redis 映射并异步转发给数采平台</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${mqtt.enabled:false} && ${darwin.integration.enabled:false}")
public class CollectUrlRequestHandler {

    private final OssAuthRequestProducer ossAuthRequestProducer;
    private final DeviceTenantResolver tenantResolver;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;

    /** 同 requestId 去重窗口（毫秒），防止设备重复上报同一 requestId */
    @Value("${mqtt.collect-url-request-dedup-ms:60000}")
    private long requestDedupMs;

    /** 同 deviceCode 转发节流（毫秒），设备每次重试会换新 requestId，需按设备限流 */
    @Value("${mqtt.collect-url-device-throttle-ms:45000}")
    private long deviceThrottleMs;

    public void handle(String deviceCode, String payload) {
        CollectUrlRequestMsg msg;
        try {
            msg = objectMapper.readValue(payload, CollectUrlRequestMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] collectUrlRequest 反序列化失败 deviceCode={} payload={}",
                    deviceCode, payload, e);
            return;
        }
        log.info("[DataCollect] 收到 collectUrlRequest deviceCode={} payload={}",
                deviceCode, payload);
        if (msg.getRequestId() == null || msg.getRequestId().isBlank()) {
            log.warn("[DataCollect] collectUrlRequest 缺少 requestId deviceCode={}", deviceCode);
            return;
        }
        if (isDuplicateRequest(msg.getRequestId())) {
            log.debug("[DataCollect] 跳过重复 collectUrlRequest requestId={} deviceCode={}",
                    msg.getRequestId(), deviceCode);
            return;
        }
        if (isDeviceThrottled(deviceCode)) {
            log.debug("[DataCollect] 跳过节流窗口内 collectUrlRequest deviceCode={} requestId={}",
                    deviceCode, msg.getRequestId());
            return;
        }

        String tenant = tenantResolver.resolveTenantId(deviceCode);
        ossAuthRequestProducer.sendAndStore(
                msg.getRequestId(), tenant, deviceCode, null, MDC.get("traceId"));
        String topic = "device/" + deviceCode + "/" + DataCollectConstant.MQTT_UP_COLLECT_URL_REQUEST;
        logService.recordLog(null, deviceCode,
                DeviceConstant.OperationType.DATA_COLLECT,
                "设备请求 OSS 上传授权，已转发数采平台",
                OperationLogDetail.ofRequest(msg.getRequestId(), topic),
                DeviceConstant.OperationSource.DEVICE, "PENDING", null, null, null);
    }

    private boolean isDuplicateRequest(String requestId) {
        String key = DataCollectConstant.REDIS_COLLECT_URL_REQ_DEDUP_PREFIX + requestId;
        long ttlMs = Math.max(requestDedupMs, 1000L);
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", ttlMs, TimeUnit.MILLISECONDS);
        return !Boolean.TRUE.equals(isNew);
    }

    private boolean isDeviceThrottled(String deviceCode) {
        String key = DataCollectConstant.REDIS_COLLECT_URL_DEVICE_THROTTLE_PREFIX + deviceCode;
        long ttlMs = Math.max(deviceThrottleMs, 1000L);
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", ttlMs, TimeUnit.MILLISECONDS);
        return !Boolean.TRUE.equals(isNew);
    }
}
