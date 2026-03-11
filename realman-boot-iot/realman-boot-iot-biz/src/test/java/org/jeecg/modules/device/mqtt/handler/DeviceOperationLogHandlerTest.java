package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 使用真实 AES 加解密逻辑的设备操作日志上报处理测试。
 */
public class DeviceOperationLogHandlerTest {

    private CommandEncryptService encryptService;
    private ObjectMapper objectMapper;
    private IDeviceOperationLogService logService;

    private DeviceOperationLogHandler handler;

    @BeforeEach
    void setUp() {
        // CommandEncryptService 依赖的 Redis mock
        StringRedisTemplate aesRedis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> aesOps = Mockito.mock(ValueOperations.class);
        when(aesRedis.opsForValue()).thenReturn(aesOps);

        encryptService = new CommandEncryptService(aesRedis);
        objectMapper = new ObjectMapper();
        logService = Mockito.mock(IDeviceOperationLogService.class);

        handler = new DeviceOperationLogHandler(
                encryptService,
                objectMapper,
                logService
        );
    }

    @Test
    void testHandleOperationLogWithRealEncryption() throws Exception {
        String deviceCode = "DEV001";
        long opTime = System.currentTimeMillis();

        // 1. 构造明文 OperationLogReport
        MqttMessageModel.OperationLogReport report = MqttMessageModel.OperationLogReport.builder()
                .operationType("LOCAL_RESTART")
                .operationDesc("device rebooted locally")
                .operationDetail("{\"reason\":\"watchdog\"}")
                .operationResult("SUCCESS")
                .operationTime(opTime)
                .build();

        String plainJson = objectMapper.writeValueAsString(report);

        // 2. 正式逻辑加密
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        // 3. 调用处理器
        handler.handle(deviceCode, encPayload);

        // 4. 校验日志服务入参
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        Mockito.verify(logService).recordLog(
                Mockito.isNull(),
                Mockito.eq(deviceCode),
                Mockito.eq("LOCAL_RESTART"),
                Mockito.eq("device rebooted locally"),
                Mockito.eq("{\"reason\":\"watchdog\"}"),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.isNull(),
                Mockito.isNull(),
                timeCaptor.capture()
        );

        LocalDateTime operationTime = timeCaptor.getValue();
        assertThat(operationTime).isNotNull();
    }
}

