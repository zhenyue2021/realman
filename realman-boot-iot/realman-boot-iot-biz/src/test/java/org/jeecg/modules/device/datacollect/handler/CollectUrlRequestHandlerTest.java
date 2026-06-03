package org.jeecg.modules.device.datacollect.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.jeecg.modules.device.datacollect.service.DeviceTenantResolver;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectUrlRequestHandlerTest {

    private OssAuthRequestProducer producer;
    private DeviceTenantResolver tenantResolver;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private CollectUrlRequestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        producer = Mockito.mock(OssAuthRequestProducer.class);
        tenantResolver = Mockito.mock(DeviceTenantResolver.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        handler = new CollectUrlRequestHandler(producer, tenantResolver, redisTemplate,
                new ObjectMapper(), Mockito.mock(IDeviceOperationLogService.class));
        Field dedup = CollectUrlRequestHandler.class.getDeclaredField("requestDedupMs");
        dedup.setAccessible(true);
        dedup.set(handler, 60_000L);
        Field throttle = CollectUrlRequestHandler.class.getDeclaredField("deviceThrottleMs");
        throttle.setAccessible(true);
        throttle.set(handler, 45_000L);
    }

    private void mockDedupAndThrottleAllow() {
        when(valueOps.setIfAbsent(any(), eq("1"), any(Long.class), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    }

    @Test
    @DisplayName("deviceCode 节流：同设备第二次请求不转发")
    void deviceThrottledOnSecondRequest() throws Exception {
        when(tenantResolver.resolveTenantId("DEV001")).thenReturn("100");
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_REQ_DEDUP_PREFIX + "req-1"), eq("1"), eq(60_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_DEVICE_THROTTLE_PREFIX + "DEV001"), eq("1"), eq(45_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_REQ_DEDUP_PREFIX + "req-2"), eq("1"), eq(60_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_DEVICE_THROTTLE_PREFIX + "DEV001"), eq("1"), eq(45_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(false);

        handler.handle("DEV001", "{\"requestId\":\"req-1\",\"timestamp\":1}");
        handler.handle("DEV001", "{\"requestId\":\"req-2\",\"timestamp\":2}");

        verify(tenantResolver, times(1)).resolveTenantId("DEV001");
        verify(producer, times(1)).sendAndStore(any(), eq("100"), eq("DEV001"), eq(null), eq(null));
    }

    @Test
    @DisplayName("requestId 去重：窗口内重复 requestId 跳过")
    void duplicateRequestIdSkipped() throws Exception {
        when(tenantResolver.resolveTenantId("DEV002")).thenReturn("200");
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_REQ_DEDUP_PREFIX + "req-dup"), eq("1"), eq(60_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true)
                .thenReturn(false);
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_DEVICE_THROTTLE_PREFIX + "DEV002"), eq("1"), eq(45_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);

        String payload = "{\"requestId\":\"req-dup\",\"timestamp\":1}";
        handler.handle("DEV002", payload);
        handler.handle("DEV002", payload);

        verify(producer, times(1)).sendAndStore(eq("req-dup"), eq("200"), eq("DEV002"), eq(null), eq(null));
    }

    @Test
    @DisplayName("deviceCode 节流：窗口内新 requestId 也跳过转发")
    void deviceThrottleSkipsNewRequestId() throws Exception {
        when(tenantResolver.resolveTenantId("DEV003")).thenReturn("300");
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_REQ_DEDUP_PREFIX + "req-a"), eq("1"), eq(60_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_REQ_DEDUP_PREFIX + "req-b"), eq("1"), eq(60_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_COLLECT_URL_DEVICE_THROTTLE_PREFIX + "DEV003"), eq("1"), eq(45_000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true)
                .thenReturn(false);

        handler.handle("DEV003", "{\"requestId\":\"req-a\",\"timestamp\":1}");
        handler.handle("DEV003", "{\"requestId\":\"req-b\",\"timestamp\":2}");

        verify(producer, times(1)).sendAndStore(eq("req-a"), eq("300"), eq("DEV003"), eq(null), eq(null));
    }

    @Test
    @DisplayName("缺少 requestId 时不转发")
    void missingRequestIdIgnored() throws Exception {
        handler.handle("DEV004", "{\"timestamp\":1}");
        verify(producer, never()).sendAndStore(any(), any(), any(), any(), any());
        verify(tenantResolver, never()).resolveTenantId(any());
        verify(valueOps, never()).setIfAbsent(any(), any(), any(Long.class), any(TimeUnit.class));
    }
}
