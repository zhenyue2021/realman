package org.jeecg.modules.device.datacollect.handler;

import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.jeecg.modules.device.datacollect.service.DeviceTenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectUrlRequestHandlerTest {

    private OssAuthRequestProducer producer;
    private DeviceTenantResolver tenantResolver;
    private CollectUrlRequestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        producer = Mockito.mock(OssAuthRequestProducer.class);
        tenantResolver = Mockito.mock(DeviceTenantResolver.class);
        handler = new CollectUrlRequestHandler(producer, tenantResolver, new ObjectMapper());
        Field dedup = CollectUrlRequestHandler.class.getDeclaredField("requestDedupMs");
        dedup.setAccessible(true);
        dedup.set(handler, 60_000L);
        Field throttle = CollectUrlRequestHandler.class.getDeclaredField("deviceThrottleMs");
        throttle.setAccessible(true);
        throttle.set(handler, 45_000L);
    }

    @Test
    @DisplayName("deviceCode 节流：同设备第二次请求不转发")
    void deviceThrottledOnSecondRequest() throws Exception {
        when(tenantResolver.resolveTenantId("DEV001")).thenReturn("100");

        handler.handle("DEV001", "{\"requestId\":\"req-1\",\"timestamp\":1}");
        handler.handle("DEV001", "{\"requestId\":\"req-2\",\"timestamp\":2}");

        verify(tenantResolver, times(1)).resolveTenantId("DEV001");
        verify(producer, times(1)).sendAndStore(any(), eq("100"), eq("DEV001"), eq(null), eq(null));
    }

    @Test
    @DisplayName("requestId 去重：窗口内重复 requestId 跳过")
    void duplicateRequestIdSkipped() throws Exception {
        when(tenantResolver.resolveTenantId("DEV002")).thenReturn("200");

        String payload = "{\"requestId\":\"req-dup\",\"timestamp\":1}";
        handler.handle("DEV002", payload);
        handler.handle("DEV002", payload);

        verify(producer, times(1)).sendAndStore(eq("req-dup"), eq("200"), eq("DEV002"), eq(null), eq(null));
    }

    @Test
    @DisplayName("deviceCode 节流：窗口内新 requestId 也跳过转发")
    void deviceThrottleSkipsNewRequestId() throws Exception {
        when(tenantResolver.resolveTenantId("DEV003")).thenReturn("300");

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
    }
}
