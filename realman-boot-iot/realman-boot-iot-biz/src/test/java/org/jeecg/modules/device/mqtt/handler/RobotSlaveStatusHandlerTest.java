package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RobotSlaveStatusHandlerTest {

    private DeviceWebSocketServer webSocketServer;
    private IotDeviceMapper deviceMapper;
    private CommandEncryptService encryptService;
    private StringRedisTemplate redisTemplate;
    private Executor deviceNotifyExecutor;
    private RobotSlaveStatusHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        encryptService = Mockito.mock(CommandEncryptService.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        deviceNotifyExecutor = Mockito.mock(Executor.class);
        handler = new RobotSlaveStatusHandler(
                webSocketServer,
                new ObjectMapper(),
                deviceMapper,
                Mockito.mock(IotDeviceStatusMapper.class),
                encryptService,
                redisTemplate);
        Field executorField = RobotSlaveStatusHandler.class.getDeclaredField("deviceNotifyExecutor");
        executorField.setAccessible(true);
        executorField.set(handler, deviceNotifyExecutor);
    }

    @Test
    @DisplayName("slave 状态：Redis 原子写入 + WS 异步推送")
    void slaveStatusBuffersAndAsyncPushes() {
        IotDevice device = new IotDevice();
        device.setDeviceCode("ROBOT001");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(encryptService.decryptFromDevice(eq("ROBOT001"), any())).thenReturn("{\"v\":1}");
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any())).thenReturn(1L);

        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            task.run();
            return null;
        }).when(deviceNotifyExecutor).execute(Mockito.any());

        handler.handle("ROBOT001", "enc");

        verify(redisTemplate).execute(any(RedisScript.class), any(List.class), eq("{\"v\":1}"), eq("ROBOT001"));
        verify(deviceNotifyExecutor).execute(Mockito.any());
        verify(webSocketServer).pushRobotStatus("ROBOT001", "{\"v\":1}");
    }

    @Test
    @DisplayName("未知设备：不写 Redis、不推送 WS")
    void unknownDeviceIgnored() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(encryptService.decryptFromDevice(any(), any())).thenReturn("{}");

        handler.handle("UNKNOWN", "enc");

        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any(), any());
        verify(deviceNotifyExecutor, never()).execute(Mockito.any());
    }
}
