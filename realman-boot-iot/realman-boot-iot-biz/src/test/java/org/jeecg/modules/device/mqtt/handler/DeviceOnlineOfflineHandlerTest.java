package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.PendingSyncService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceOnlineOfflineHandlerTest {

    private IotDeviceMapper deviceMapper;
    private StringRedisTemplate redisTemplate;
    private DeviceWebSocketServer webSocketServer;
    private PendingSyncService pendingSyncService;
    private DeviceOnlineOfflineHandler handler;

    @BeforeEach
    void setUp() {
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        pendingSyncService = Mockito.mock(PendingSyncService.class);
        handler = new DeviceOnlineOfflineHandler(
                deviceMapper,
                redisTemplate,
                webSocketServer,
                new ObjectMapper(),
                Mockito.mock(IDeviceOperationLogService.class),
                pendingSyncService,
                Mockito.mock(IIotDeviceRoomService.class));
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("设备上线：补推待同步消息走异步接口")
    void onlineTriggersAsyncPendingSync() {
        IotDevice device = new IotDevice();
        device.setId("id1");
        device.setDeviceCode("DEV001");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        String topic = "$SYS/brokers/emqx@127.0.0.1/clients/DEV001/connected";
        String payload = "{\"username\":\"DEV001\",\"clientid\":\"DEV001\"}";
        handler.handleOnline(topic, payload);

        verify(pendingSyncService).flushPendingMessagesAsync("DEV001");
        verify(webSocketServer).pushDeviceOnlineStatus("DEV001", true);
    }
}
