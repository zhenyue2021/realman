package org.jeecg.modules.device.service.impl.device;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.springframework.stereotype.Component;

/**
 * 设备实体加载与存在性校验（供各子域 Service 复用）。
 */
@Component
@RequiredArgsConstructor
public class IotDeviceSupport {

    private final IotDeviceMapper deviceMapper;

    public IotDevice require(String deviceId) {
        IotDevice d = deviceMapper.selectById(deviceId);
        if (d == null) {
            throw new RuntimeException("设备不存在: " + deviceId);
        }
        return d;
    }
}
