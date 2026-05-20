package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 使用真实 AES 加解密逻辑的设备状态上报处理单元测试。
 *
 * 流程：
 *  1. 构造 StatusReport 对象并序列化为 JSON；
 *  2. 使用 CommandEncryptService.encryptForDevice() 按正式逻辑加密；
 *  3. 调用 DeviceStatusHandler.handle(deviceCode, encPayload)；
 *  4. 验证已经按预期调用 DB / Redis / WebSocket 等接口。
 */
public class DeviceStatusHandlerTest {

    private IotDeviceMapper deviceMapper;
    private IotDeviceStatusMapper statusMapper;
    private StringRedisTemplate redisTemplate;
    private DeviceWebSocketServer webSocketServer;
    private CommandEncryptService encryptService;
    private ObjectMapper objectMapper;

    private DeviceStatusHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotDevice.class);
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        statusMapper = Mockito.mock(IotDeviceStatusMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        objectMapper = new ObjectMapper();

        // 为 CommandEncryptService 准备一个可用的 StringRedisTemplate mock（只用于缓存 AES Key）
        StringRedisTemplate aesRedis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> aesOps = Mockito.mock(ValueOperations.class);
        when(aesRedis.opsForValue()).thenReturn(aesOps);

        encryptService = new CommandEncryptService(aesRedis);

        handler = new DeviceStatusHandler(
                deviceMapper,
                statusMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                webSocketServer
        );
    }

    @Test
    void testHandleStatusReportWithRealEncryption() throws Exception {
        String deviceCode = "DEV001";

        // 准备设备基础信息
        IotDevice device = new IotDevice();
        device.setId("dev-id-1");
        device.setDeviceCode(deviceCode);
        device.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
        when(deviceMapper.selectOne(Mockito.any())).thenReturn(device);

        // Redis value ops mock
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 1. 构造明文 StatusReport
        long now = System.currentTimeMillis();
        MqttMessageModel.StatusReport report = MqttMessageModel.StatusReport.builder()
                .temperature(new BigDecimal("25.5"))
                .humidity(new BigDecimal("60.0"))
                .batteryLevel(new BigDecimal("90"))
                .signalStrength(-70)
                .runStatus(1)
                .longitude(new BigDecimal("120.123456"))
                .latitude(new BigDecimal("30.123456"))
                .timestamp(now)
                .build();

        String plainJson = objectMapper.writeValueAsString(report);

        // 2. 使用正式逻辑加密
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        // 3. 调用处理方法
        handler.handle(deviceCode, encPayload);

        // 4. 断言：设备状态被更新为 ONLINE，且经纬度被覆盖
        ArgumentCaptor<IotDevice> deviceCaptor = ArgumentCaptor.forClass(IotDevice.class);
        Mockito.verify(deviceMapper).updateById(deviceCaptor.capture());
        IotDevice updated = deviceCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(DeviceConstant.DeviceStatus.ONLINE);
        assertThat(updated.getLongitude()).isEqualByComparingTo(new BigDecimal("120.123456"));
        assertThat(updated.getLatitude()).isEqualByComparingTo(new BigDecimal("30.123456"));
        assertThat(updated.getLastOnlineTime()).isNotNull();

        // 5. Redis 实时状态缓存被写入
        Mockito.verify(valueOps).set(
                Mockito.eq(DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode),
                Mockito.eq(plainJson),
                Mockito.eq(DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1L),
                Mockito.any()
        );

        // 6. WebSocket 推送
        Mockito.verify(webSocketServer).pushDeviceStatus(deviceCode, plainJson);

        // 历史状态写入由 persistAsync 负责，异步场景下此处不强制校验 insert
    }

    @Test
    void refreshKeepalivePresenceUpdatesRedisWithoutDb() throws Exception {
        String deviceCode = "DEV001";

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        MqttMessageModel.StatusReport report = MqttMessageModel.StatusReport.builder()
                .timestamp(System.currentTimeMillis())
                .build();
        String plainJson = objectMapper.writeValueAsString(report);
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        handler.refreshKeepalivePresence(deviceCode, encPayload);

        Mockito.verify(valueOps).set(
                Mockito.eq(DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode),
                Mockito.eq(plainJson),
                Mockito.eq(DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1L),
                Mockito.any()
        );
        Mockito.verify(redisTemplate.opsForSet()).add(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
        Mockito.verifyNoInteractions(deviceMapper);
        Mockito.verifyNoInteractions(webSocketServer);
    }
}

