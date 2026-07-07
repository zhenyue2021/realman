package org.jeecg.modules.device.service;

import org.jeecg.modules.device.dto.DeviceProvisionRequestDTO;
import org.jeecg.modules.device.dto.DeviceProvisionResponseDTO;

/**
 * 设备 HTTP 自注册（Provision）服务。
 */
public interface IDeviceProvisionService {

    DeviceProvisionResponseDTO provision(DeviceProvisionRequestDTO request);
}
