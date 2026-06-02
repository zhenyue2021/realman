package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.PendingSyncService;
import org.jeecg.modules.device.service.impl.master.TeleopRelationCacheService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceOnlineOfflineHandlerTest {

    private IotDeviceMapper deviceMapper;
    private StringRedisTemplate redisTemplate;
    private DeviceWebSocketServer webSocketServer;
    private PendingSyncService pendingSyncService;
    private DeviceStatusPersistenceService statusPersistenceService;
    private DeviceOnlineOfflineHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        pendingSyncService = Mockito.mock(PendingSyncService.class);
        statusPersistenceService = Mockito.mock(DeviceStatusPersistenceService.class);
        handler = new DeviceOnlineOfflineHandler(
                deviceMapper,
                redisTemplate,
                webSocketServer,
                new ObjectMapper(),
                Mockito.mock(IDeviceOperationLogService.class),
                pendingSyncService,
                Mockito.mock(IIotDeviceRoomService.class),
                new DeviceDbStatusCache(redisTemplate),
                Mockito.mock(TeleopRelationCacheService.class),
                statusPersistenceService);
        Field idempotentSeconds = DeviceOnlineOfflineHandler.class.getDeclaredField("sysEventIdempotentSeconds");
        idempotentSeconds.setAccessible(true);
        idempotentSeconds.set(handler, 15L);
        Field reconcileSideFx = DeviceOnlineOfflineHandler.class.getDeclaredField("reconcileSideFxIdempotentSeconds");
        reconcileSideFx.setAccessible(true);
        reconcileSideFx.set(handler, 60L);
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), eq("1"), any(Long.class), eq(TimeUnit.SECONDS))).thenReturn(true);
    }

    @Test
    @DisplayName("设备上线：补推待同步消息走异步接口")
    void onlineTriggersAsyncPendingSync() {
        IotDevice device = new IotDevice();
        device.setId("id1");
        device.setDeviceCode("DEV001");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);

        String topic = "$SYS/brokers/emqx@127.0.0.1/clients/DEV001/connected";
        String payload = "{\"username\":\"DEV001\",\"clientid\":\"DEV001\"}";
        handler.handleOnline(topic, payload);

        verify(pendingSyncService).flushPendingMessagesAsync("DEV001");
        verify(webSocketServer).pushDeviceOnlineStatus("DEV001", true);
        verify(statusPersistenceService).persistConnectionStatus(
                eq(device),
                eq(DeviceConstant.DeviceStatus.STATUS_RECORD_ONLINE),
                eq("$SYS connected"),
                eq(null));
    }

    @Test
    @DisplayName("mqtt-auth 上线：补推待同步消息")
    void authConnectTriggersAsyncPendingSync() {
        IotDevice device = new IotDevice();
        device.setId("id1");
        device.setDeviceCode("DEV001");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);

        handler.handleDeviceConnectedFromAuth("DEV001");

        verify(pendingSyncService).flushPendingMessagesAsync("DEV001");
        verify(webSocketServer).pushDeviceOnlineStatus("DEV001", true);
        verify(statusPersistenceService).persistConnectionStatus(
                eq(device),
                eq(DeviceConstant.DeviceStatus.STATUS_RECORD_ONLINE),
                eq("mqtt-auth"),
                eq(null));
    }

    @Test
    @DisplayName("设备上线：重复 $SYS 事件被 Redis 幂等跳过")
    void onlineSkippedWhenIdempotentLockNotAcquired() {
        ValueOperations<String, String> valueOps = redisTemplate.opsForValue();
        when(valueOps.setIfAbsent(any(), eq("1"), any(Long.class), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        String topic = "$SYS/brokers/emqx@127.0.0.1/clients/DEV001/connected";
        String payload = "{\"username\":\"DEV001\",\"clientid\":\"DEV001\"}";
        handler.handleOnline(topic, payload);

        verify(deviceMapper, never()).updateById(any(IotDevice.class));
        verify(pendingSyncService, never()).flushPendingMessagesAsync(any());
        verify(webSocketServer, never()).pushDeviceOnlineStatus(any(), eq(true));
    }
}
