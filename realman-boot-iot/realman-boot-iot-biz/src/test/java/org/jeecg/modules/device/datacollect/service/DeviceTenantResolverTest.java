package org.jeecg.modules.device.datacollect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceTenantResolverTest {

    private IotDeviceMapper deviceMapper;
    private DeviceTenantResolver resolver;

    @BeforeEach
    void setUp() {
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        resolver = new DeviceTenantResolver(deviceMapper);
    }

    @Test
    @DisplayName("同 deviceCode 第二次命中本地缓存")
    void tenantCachedOnSecondLookup() {
        IotDevice device = new IotDevice();
        device.setTenantId(100);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        resolver.resolveTenantId("DEV001");
        resolver.resolveTenantId("DEV001");

        verify(deviceMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
    }
}
