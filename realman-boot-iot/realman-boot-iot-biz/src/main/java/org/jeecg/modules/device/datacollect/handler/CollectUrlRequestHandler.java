package org.jeecg.modules.device.datacollect.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlRequestMsg;
import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.jeecg.modules.device.datacollect.service.DeviceTenantResolver;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT 上行处理：机器人请求 OSS 上传授权（device/{code}/datacollect/collectUrlRequest）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解析消息体，校验 requestId 非空</li>
 *   <li>requestId / deviceCode 去重，抑制设备重试风暴</li>
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
    private final ObjectMapper objectMapper;

    /** 同 requestId 去重窗口（毫秒），防止设备重复上报同一 requestId */
    @Value("${mqtt.collect-url-request-dedup-ms:60000}")
    private long requestDedupMs;

    /** 同 deviceCode 转发节流（毫秒），设备每次重试会换新 requestId，需按设备限流 */
    @Value("${mqtt.collect-url-device-throttle-ms:45000}")
    private long deviceThrottleMs;

    private final ConcurrentHashMap<String, Long> recentRequestIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastForwardByDevice = new ConcurrentHashMap<>();

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
    }

    private boolean isDuplicateRequest(String requestId) {
        long now = System.currentTimeMillis();
        purgeExpiredRequestIds(now);
        Long last = recentRequestIds.putIfAbsent(requestId, now);
        if (last == null) {
            return false;
        }
        if (now - last < requestDedupMs) {
            return true;
        }
        recentRequestIds.put(requestId, now);
        return false;
    }

    private boolean isDeviceThrottled(String deviceCode) {
        long now = System.currentTimeMillis();
        purgeExpiredDeviceForwards(now);
        Long last = lastForwardByDevice.putIfAbsent(deviceCode, now);
        if (last == null) {
            return false;
        }
        if (now - last < deviceThrottleMs) {
            return true;
        }
        lastForwardByDevice.put(deviceCode, now);
        return false;
    }

    private void purgeExpiredRequestIds(long now) {
        if (recentRequestIds.size() < 512) {
            return;
        }
        recentRequestIds.entrySet().removeIf(e -> now - e.getValue() > requestDedupMs);
    }

    private void purgeExpiredDeviceForwards(long now) {
        if (lastForwardByDevice.size() < 256) {
            return;
        }
        lastForwardByDevice.entrySet().removeIf(e -> now - e.getValue() > deviceThrottleMs);
    }
}
