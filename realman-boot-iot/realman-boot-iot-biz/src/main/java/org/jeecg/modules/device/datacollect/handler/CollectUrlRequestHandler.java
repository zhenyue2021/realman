package org.jeecg.modules.device.datacollect.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlRequestMsg;
import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
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
 *   <li>查询设备获取 tenantId（本地缓存，减少 DB 压力）</li>
 *   <li>调用 {@link OssAuthRequestProducer#sendAndStore} 存 Redis 映射并转发给数采平台</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${mqtt.enabled:false} && ${darwin.integration.enabled:false}")
public class CollectUrlRequestHandler {

    private static final long TENANT_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final OssAuthRequestProducer ossAuthRequestProducer;
    private final IotDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;

    /** 同 requestId 去重窗口（毫秒），防止设备 40s 重试风暴重复查库/发 MQ */
    @Value("${mqtt.collect-url-request-dedup-ms:60000}")
    private long requestDedupMs;

    private final ConcurrentHashMap<String, TenantCacheEntry> tenantByDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentRequestIds = new ConcurrentHashMap<>();

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

        String tenant = resolveTenantId(deviceCode);

        try {
            ossAuthRequestProducer.sendAndStore(
                    msg.getRequestId(), tenant, deviceCode, null, MDC.get("traceId"));
        } catch (Exception e) {
            log.error("[DataCollect] OSS授权请求转发失败 requestId={} deviceCode={}",
                    msg.getRequestId(), deviceCode, e);
        }
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

    private void purgeExpiredRequestIds(long now) {
        if (recentRequestIds.size() < 512) {
            return;
        }
        recentRequestIds.entrySet().removeIf(e -> now - e.getValue() > requestDedupMs);
    }

    private String resolveTenantId(String deviceCode) {
        long now = System.currentTimeMillis();
        TenantCacheEntry cached = tenantByDevice.get(deviceCode);
        if (cached != null && now - cached.cachedAtMs < TENANT_CACHE_TTL_MS) {
            return cached.tenantId;
        }
        IotDevice device = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, deviceCode));
        String tenant = device != null && device.getTenantId() != null
                ? String.valueOf(device.getTenantId()) : "";
        tenantByDevice.put(deviceCode, new TenantCacheEntry(tenant, now));
        return tenant;
    }

    private record TenantCacheEntry(String tenantId, long cachedAtMs) {
    }
}
