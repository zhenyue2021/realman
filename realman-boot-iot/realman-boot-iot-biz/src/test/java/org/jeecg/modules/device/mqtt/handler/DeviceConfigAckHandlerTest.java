package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 使用真实 AES 加解密逻辑的配置同步确认处理单元测试。
 */
public class DeviceConfigAckHandlerTest {

    private IotDeviceConfigMapper configMapper;
    private StringRedisTemplate redisTemplate;
    private IDeviceOperationLogService logService;
    private CommandEncryptService encryptService;
    private ObjectMapper objectMapper;

    private DeviceConfigAckHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        // 单元测试无 Spring 时 MyBatis-Plus 不初始化实体 Lambda 缓存，LambdaUpdateWrapper 会报 can not find lambda cache
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotDeviceConfig.class);

        configMapper = Mockito.mock(IotDeviceConfigMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        logService = Mockito.mock(IDeviceOperationLogService.class);
        objectMapper = new ObjectMapper();

        // CommandEncryptService 依赖的 Redis mock
        StringRedisTemplate aesRedis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> aesOps = Mockito.mock(ValueOperations.class);
        when(aesRedis.opsForValue()).thenReturn(aesOps);

        encryptService = new CommandEncryptService(aesRedis);

        handler = new DeviceConfigAckHandler(
                configMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                logService
        );
    }

    @Test
    void testHandleConfigAckSuccessWithRealEncryption() throws Exception {
        String deviceCode = "DEV001";
        String commandId = "CMD-123";

        // Redis ops
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 1. 构造明文 ConfigAck
        MqttMessageModel.ConfigAck ack = MqttMessageModel.ConfigAck.builder()
                .commandId(commandId)
                .code(0)
                .message("OK")
                .timestamp(System.currentTimeMillis())
                .build();

        String plainJson = objectMapper.writeValueAsString(ack);

        // 2. 正式逻辑加密
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);
        // 3. 处理
        handler.handle(deviceCode, encPayload);

        // 4. Redis 中等待 Key 被删除
        Mockito.verify(redisTemplate).delete(
                DeviceConstant.RedisKey.CONFIG_SYNC_PREFIX + deviceCode + ":" + commandId
        );

        // 5. 批量更新配置记录：PENDING → SUCCESS
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<IotDeviceConfig>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(configMapper).update(Mockito.isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<IotDeviceConfig> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper).isNotNull();

        // 6. 操作日志被写入一次即可（不深入校验内容）
        Mockito.verify(logService).recordLog(
                Mockito.isNull(),
                Mockito.eq(deviceCode),
                Mockito.eq(DeviceConstant.OperationType.PARAM_MODIFY),
                Mockito.contains("成功"),
                Mockito.anyString(),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull()
        );
    }
}

