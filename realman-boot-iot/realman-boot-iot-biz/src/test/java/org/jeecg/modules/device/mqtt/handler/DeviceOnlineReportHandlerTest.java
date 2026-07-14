package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.impl.master.TeleopRelationCacheService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceOnlineReportHandlerTest {

    private IotDeviceMapper deviceMapper;
    private TeleopRelationCacheService teleopRelationCacheService;
    private StringRedisTemplate redisTemplate;
    private DeviceWebSocketServer webSocketServer;
    private DeviceOnlineReportHandler handler;

    @BeforeEach
    void setUp() {
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        teleopRelationCacheService = Mockito.mock(TeleopRelationCacheService.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        handler = new DeviceOnlineReportHandler(
                deviceMapper,
                new ObjectMapper(),
                Mockito.mock(IDeviceOperationLogService.class),
                teleopRelationCacheService,
                redisTemplate,
                webSocketServer);
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("机器人 deviceOnline：绑定主控在线时推送 ROBOT_ONLINE_STATUS")
    void robotOnlineNotifiesBoundOnlineMaster() {
        IotDevice robot = new IotDevice();
        robot.setId("id1");
        robot.setDeviceCode("R001");
        robot.setDeviceType(DeviceConstant.DeviceTypeInteger.ROBOT);
        robot.setDeviceModel("RM-01");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(robot);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);
        when(teleopRelationCacheService.getMasterByRobot("R001")).thenReturn("M001");
        when(redisTemplate.opsForSet().isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, "M001"))
                .thenReturn(true);

        String payload = "{\"timestamp\":1718000000000,\"deviceSn\":\"R001\","
                + "\"payload\":{\"deviceType\":\"RM-01\",\"version\":\"1.0.0\"}}";
        handler.handle("R001", payload);

        verify(webSocketServer).pushRobotOnlineToMaster(eq("M001"), any(String.class));
    }

    @Test
    @DisplayName("机器人 deviceOnline：无绑定主控时不推送")
    void robotOnlineSkippedWhenNoBoundMaster() {
        IotDevice robot = new IotDevice();
        robot.setId("id1");
        robot.setDeviceCode("R001");
        robot.setDeviceType(DeviceConstant.DeviceTypeInteger.ROBOT);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(robot);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);
        when(teleopRelationCacheService.getMasterByRobot("R001")).thenReturn(null);

        handler.handle("R001", "{\"timestamp\":1718000000000}");

        verify(webSocketServer, never()).pushRobotOnlineToMaster(any(), any());
    }

    @Test
    @DisplayName("机器人 deviceOnline：绑定主控离线时不推送")
    void robotOnlineSkippedWhenMasterOffline() {
        IotDevice robot = new IotDevice();
        robot.setId("id1");
        robot.setDeviceCode("R001");
        robot.setDeviceType(DeviceConstant.DeviceTypeInteger.ROBOT);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(robot);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);
        when(teleopRelationCacheService.getMasterByRobot("R001")).thenReturn("M001");
        when(redisTemplate.opsForSet().isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, "M001"))
                .thenReturn(false);

        handler.handle("R001", "{\"timestamp\":1718000000000}");

        verify(webSocketServer, never()).pushRobotOnlineToMaster(any(), any());
    }

    @Test
    @DisplayName("主控 deviceOnline：不触发机器人上线通知")
    void controllerOnlineDoesNotNotifyMaster() {
        IotDevice controller = new IotDevice();
        controller.setId("id1");
        controller.setDeviceCode("M001");
        controller.setDeviceType(DeviceConstant.DeviceTypeInteger.CONTROLLER);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(controller);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);

        handler.handle("M001", "{\"timestamp\":1718000000000}");

        verify(webSocketServer, never()).pushRobotOnlineToMaster(any(), any());
        verify(teleopRelationCacheService, never()).getMasterByRobot(any());
    }
}
