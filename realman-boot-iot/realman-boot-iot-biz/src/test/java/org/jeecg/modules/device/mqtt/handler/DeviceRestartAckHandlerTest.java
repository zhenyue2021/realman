package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.when;

/**
 * 使用真实 AES 加解密逻辑的重启确认上报处理测试。
 */
public class DeviceRestartAckHandlerTest {

    private CommandEncryptService encryptService;
    private ObjectMapper objectMapper;
    private IDeviceOperationLogService logService;

    private DeviceRestartAckHandler handler;

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

        handler = new DeviceRestartAckHandler(
                encryptService,
                objectMapper,
                logService
        );
    }

    @Test
    void testHandleRestartAckSuccessWithRealEncryption() throws Exception {
        String deviceCode = "DEV001";
        String commandId = "RST-001";

        // 1. 构造明文 RestartAck
        MqttMessageModel.RestartAck ack = MqttMessageModel.RestartAck.builder()
                .commandId(commandId)
                .code(0)
                .message("will reboot now")
                .timestamp(System.currentTimeMillis())
                .build();

        String plainJson = objectMapper.writeValueAsString(ack);

        // 2. 按正式逻辑加密
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        // 3. 调用处理器
        handler.handle(deviceCode, encPayload);

        // 4. 日志服务被调用一次即可（不细查入参）
        Mockito.verify(logService).recordLog(
                Mockito.isNull(),
                Mockito.eq(deviceCode),
                Mockito.eq(DeviceConstant.OperationType.REMOTE_RESTART),
                Mockito.contains("执行"),
                Mockito.anyString(),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.anyString(),
                Mockito.isNull(),
                Mockito.isNull()
        );
    }
}

