package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DeviceStatusHandler 单元测试（双投递：refreshKeepalivePresence + handle）。
 */
public class DeviceStatusHandlerTest {

    private IotDeviceMapper                  deviceMapper;
    private StringRedisTemplate              redisTemplate;
    private DeviceWebSocketServer            webSocketServer;
    private CommandEncryptService            encryptService;
    private ObjectMapper                     objectMapper;
    private DeviceStatusPersistenceService   persistenceService;

    private DeviceStatusHandler handler;

    @BeforeEach
    void setUp() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, IotDevice.class);

        deviceMapper       = Mockito.mock(IotDeviceMapper.class);
        redisTemplate      = Mockito.mock(StringRedisTemplate.class);
        webSocketServer    = Mockito.mock(DeviceWebSocketServer.class);
        persistenceService = Mockito.mock(DeviceStatusPersistenceService.class);
        objectMapper       = new ObjectMapper();

        StringRedisTemplate aesRedis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> aesOps = Mockito.mock(ValueOperations.class);
        when(aesRedis.opsForValue()).thenReturn(aesOps);
        encryptService = new CommandEncryptService(aesRedis);

        handler = new DeviceStatusHandler(
                deviceMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                webSocketServer,
                persistenceService
        );

        // executePipelined 有两个重载(RedisCallback/SessionCallback)，需明确类型避免歧义
        when(redisTemplate.executePipelined(Mockito.<RedisCallback<Object>>any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void testHandleStatusReportWithRealEncryption() throws Exception {
        String deviceCode = "DEV001";

        IotDevice device = new IotDevice();
        device.setId("dev-id-1");
        device.setDeviceCode(deviceCode);
        device.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
        when(deviceMapper.selectOne(Mockito.any())).thenReturn(device);

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
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        handler.handle(deviceCode, encPayload);

        ArgumentCaptor<IotDevice> updateCaptor = ArgumentCaptor.forClass(IotDevice.class);
        Mockito.verify(persistenceService).updateDeviceOnline(updateCaptor.capture());
        IotDevice updated = updateCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(DeviceConstant.DeviceStatus.ONLINE);
        assertThat(updated.getLongitude()).isEqualByComparingTo(new BigDecimal("120.123456"));
        assertThat(updated.getLatitude()).isEqualByComparingTo(new BigDecimal("30.123456"));
        assertThat(updated.getLastOnlineTime()).isNotNull();

        Mockito.verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
        Mockito.verify(webSocketServer).pushDeviceStatus(deviceCode, plainJson);
        Mockito.verify(persistenceService).persistHistory(
                Mockito.eq(device),
                Mockito.any(MqttMessageModel.StatusReport.class),
                Mockito.eq(plainJson));
        // updateById 不应被直接调用，DB 写通过 persistenceService 异步执行
        Mockito.verify(deviceMapper, Mockito.never()).updateById(Mockito.any(IotDevice.class));
    }

    @Test
    void refreshKeepalivePresenceUpdatesRedisWithoutDb() {
        String deviceCode = "DEV001";

        handler.refreshKeepalivePresence(deviceCode, "ignored");

        Mockito.verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
        Mockito.verifyNoInteractions(deviceMapper);
        Mockito.verifyNoInteractions(webSocketServer);
        Mockito.verifyNoInteractions(persistenceService);
    }

    @Test
    void handleKeepaliveSkipsDbAndWebSocket() throws Exception {
        String deviceCode = "DEV001";
        String plainJson = "{\"message\":\"keepalive\",\"timestamp\":" + System.currentTimeMillis() + "}";
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        handler.handle(deviceCode, encPayload);

        Mockito.verify(redisTemplate).executePipelined(Mockito.<RedisCallback<Object>>any());
        Mockito.verifyNoInteractions(deviceMapper);
        Mockito.verifyNoInteractions(webSocketServer);
        Mockito.verifyNoInteractions(persistenceService);
    }
}
