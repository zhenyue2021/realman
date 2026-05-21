package org.jeecg.modules.device.datacollect.handler;

import org.jeecg.modules.device.datacollect.producer.FileAddressReportProducer;
import org.jeecg.modules.device.datacollect.service.DeviceTenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssAddressReportHandlerTest {

    private FileAddressReportProducer producer;
    private DeviceTenantResolver tenantResolver;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private OssAddressReportHandler handler;

    @BeforeEach
    void setUp() {
        producer = Mockito.mock(FileAddressReportProducer.class);
        tenantResolver = Mockito.mock(DeviceTenantResolver.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        handler = new OssAddressReportHandler(producer, tenantResolver, redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("tenant 缓存：同 deviceCode 第二次不查库")
    void tenantCachedOnSecondReport() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(24L), eq(TimeUnit.HOURS))).thenReturn(true);
        when(tenantResolver.resolveTenantId("DEV001")).thenReturn("100");

        String payload = "{\"oss\":{\"address\":\"oss://a\",\"businessKey\":\"bk1\",\"list\":[\"f1\"]}}";
        handler.handle("DEV001", payload);
        handler.handle("DEV001", "{\"oss\":{\"address\":\"oss://b\",\"businessKey\":\"bk2\",\"list\":[\"f2\"]}}");

        verify(tenantResolver, times(2)).resolveTenantId("DEV001");
        verify(producer, times(2)).send(any(), eq("DEV001"), eq(null), eq(null), anyString(), any(), eq(null));
    }

    @Test
    @DisplayName("重复 businessKey 跳过转发")
    void duplicateBusinessKeySkipped() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
                .thenReturn(true, false);

        String payload = "{\"oss\":{\"address\":\"oss://a\",\"businessKey\":\"bk1\",\"list\":[\"f1\"]}}";
        handler.handle("DEV002", payload);
        handler.handle("DEV002", payload);

        verify(producer, times(1)).send(any(), eq("DEV002"), eq(null), eq(null), anyString(), any(), eq(null));
        verify(tenantResolver, times(1)).resolveTenantId("DEV002");
    }

    @Test
    @DisplayName("消息不合法时不查 tenant")
    void invalidPayloadIgnored() {
        handler.handle("DEV003", "{\"oss\":{\"address\":\"oss://a\"}}");
        verify(producer, never()).send(any(), any(), any(), any(), any(), any(), any());
        verify(tenantResolver, never()).resolveTenantId(any());
    }
}
