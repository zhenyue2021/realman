package org.jeecg.modules.device.mqtt.handler;

import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.mapper.ExtParamRecordIotMapper;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtParamsRequestHandlerTest {

    private CommandEncryptService encryptService;
    private MqttPublisher mqttPublisher;
    private RedisUtil redisUtil;
    private ExtParamRecordIotMapper extParamRecordIotMapper;
    private IDeviceOperationLogService logService;
    private StringRedisTemplate stringRedisTemplate;
    private Executor mqttPublishExecutor;
    private ExtParamsRequestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        encryptService = Mockito.mock(CommandEncryptService.class);
        mqttPublisher = Mockito.mock(MqttPublisher.class);
        redisUtil = Mockito.mock(RedisUtil.class);
        extParamRecordIotMapper = Mockito.mock(ExtParamRecordIotMapper.class);
        logService = Mockito.mock(IDeviceOperationLogService.class);
        stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        mqttPublishExecutor = Mockito.mock(Executor.class);
        handler = new ExtParamsRequestHandler(
                encryptService, new ObjectMapper(), mqttPublisher, redisUtil, extParamRecordIotMapper,
                logService, stringRedisTemplate);
        Field executorField = ExtParamsRequestHandler.class.getDeclaredField("mqttPublishExecutor");
        executorField.setAccessible(true);
        executorField.set(handler, mqttPublishExecutor);
    }

    @Test
    @DisplayName("MQTT 回包提交到 mqttPublishExecutor，不在 handle 内同步 publish")
    void publishSubmittedToExecutor() throws Exception {
        when(encryptService.decryptFromDevice(anyString(), anyString())).thenReturn(
                "{\"requestId\":\"r1\",\"sourceSystem\":\"SRC\",\"targetSystem\":\"TGT\",\"bizType\":\"upload_url_request\"}");
        when(redisUtil.get("realman:ext:param:TGT:SRC")).thenReturn(
                "{\"endpoint\":\"e\",\"bucket\":\"b\",\"accessKeyId\":\"ak\"}");

        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            task.run();
            return null;
        }).when(mqttPublishExecutor).execute(Mockito.any());

        handler.handle("DEV001", "enc");

        verify(mqttPublishExecutor).execute(Mockito.any());
        verify(mqttPublisher).publishToDevice(
                eq("DEV001"),
                eq(String.format(DeviceConstant.MqttTopic.EXT_PARAMS_RESPONSE, "DEV001")),
                anyString(),
                eq(MqttConstant.MQTT_QOS.QOS_1));
    }

    @Test
    @DisplayName("反序列化失败时不提交 publish")
    void invalidPayloadSkipsPublish() throws Exception {
        when(encryptService.decryptFromDevice(anyString(), anyString())).thenReturn("not-json");
        handler.handle("DEV002", "enc");
        verify(mqttPublishExecutor, never()).execute(Mockito.any());
        verify(mqttPublisher, never()).publishToDevice(anyString(), anyString(), anyString(), anyInt());
    }
}
