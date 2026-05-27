package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceStatusPersistenceServiceTest {

    private IotDeviceMapper deviceMapper;
    private DeviceDbStatusCache dbStatusCache;
    private DeviceStatusPersistenceService service;

    @BeforeEach
    void setUp() {
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        dbStatusCache = new DeviceDbStatusCache();
        service = new DeviceStatusPersistenceService(deviceMapper, Mockito.mock(IotDeviceStatusMapper.class), dbStatusCache);
    }

    @Test
    @DisplayName("DB 已 ONLINE 时不 updateById，仅回填缓存")
    void skipUpdateWhenAlreadyOnline() {
        IotDevice device = new IotDevice();
        device.setId("id1");
        device.setDeviceCode("DEV001");
        device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        service.promoteOnlineIfOffline("DEV001");

        verify(deviceMapper, never()).updateById(any(IotDevice.class));
        assert dbStatusCache.isOnline("DEV001");
    }

    @Test
    @DisplayName("DB 为 OFFLINE 时更新 status 与 lastOnlineTime")
    void promoteWhenOffline() {
        IotDevice device = new IotDevice();
        device.setId("id2");
        device.setDeviceCode("DEV002");
        device.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);

        service.promoteOnlineIfOffline("DEV002");

        verify(deviceMapper).updateById(any(IotDevice.class));
        assert dbStatusCache.isOnline("DEV002");
    }

    @Test
    @DisplayName("DB 为 INACTIVE 时更新为 ONLINE")
    void promoteWhenInactive() {
        IotDevice device = new IotDevice();
        device.setId("id4");
        device.setDeviceCode("DEV004");
        device.setStatus(DeviceConstant.DeviceStatus.INACTIVE);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(deviceMapper.updateById(any(IotDevice.class))).thenReturn(1);

        service.promoteOnlineIfOffline("DEV004");

        verify(deviceMapper).updateById(any(IotDevice.class));
        assert dbStatusCache.isOnline("DEV004");
    }

    @Test
    @DisplayName("DISABLED 设备不自动上线")
    void skipWhenDisabled() {
        IotDevice device = new IotDevice();
        device.setId("id3");
        device.setDeviceCode("DEV003");
        device.setStatus(DeviceConstant.DeviceStatus.DISABLED);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        service.promoteOnlineIfOffline("DEV003");

        verify(deviceMapper, never()).updateById(any(IotDevice.class));
    }
}
