package org.jeecg.modules.device.datacollect.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlRequestMsg;
import org.jeecg.modules.device.datacollect.producer.OssAuthRequestProducer;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
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
    private IotDeviceMapper deviceMapper;
    private CollectUrlRequestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        producer = Mockito.mock(OssAuthRequestProducer.class);
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        handler = new CollectUrlRequestHandler(producer, deviceMapper, new ObjectMapper());
        Field dedup = CollectUrlRequestHandler.class.getDeclaredField("requestDedupMs");
        dedup.setAccessible(true);
        dedup.set(handler, 60_000L);
    }

    @Test
    @DisplayName("tenant 缓存：同 deviceCode 第二次不查库")
    void tenantCachedOnSecondRequest() throws Exception {
        IotDevice device = new IotDevice();
        device.setTenantId(100);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        String payload = "{\"requestId\":\"req-1\",\"timestamp\":1}";
        handler.handle("DEV001", payload);
        handler.handle("DEV001", "{\"requestId\":\"req-2\",\"timestamp\":2}");

        verify(deviceMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
        verify(producer, times(2)).sendAndStore(any(), eq("100"), eq("DEV001"), eq(null), eq(null));
    }

    @Test
    @DisplayName("requestId 去重：窗口内重复 requestId 跳过")
    void duplicateRequestIdSkipped() throws Exception {
        IotDevice device = new IotDevice();
        device.setTenantId(200);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        String payload = "{\"requestId\":\"req-dup\",\"timestamp\":1}";
        handler.handle("DEV002", payload);
        handler.handle("DEV002", payload);

        verify(producer, times(1)).sendAndStore(eq("req-dup"), eq("200"), eq("DEV002"), eq(null), eq(null));
    }

    @Test
    @DisplayName("缺少 requestId 时不转发")
    void missingRequestIdIgnored() throws Exception {
        handler.handle("DEV003", "{\"timestamp\":1}");
        verify(producer, never()).sendAndStore(any(), any(), any(), any(), any());
        verify(deviceMapper, never()).selectOne(any());
    }
}
