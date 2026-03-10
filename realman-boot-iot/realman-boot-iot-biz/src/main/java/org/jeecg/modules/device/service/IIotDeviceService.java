package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.vo.DeviceDetailVO;

import java.util.List;
import java.util.Map;

public interface IIotDeviceService extends IService<IotDevice> {
    IotDevice addDevice(IotDevice device);

    IPage<IotDevice> queryDevicePage(Page<IotDevice> page, DeviceRequestDTO requestDTO);

    void setAndSyncConfig(String deviceId, Map<String, Object> params);

    Map<String, Object> getDeviceMonitorStatus(String deviceId);

    DeviceDetailVO getDeviceDetail(String deviceId);

    void remoteRestart(String deviceId, String reason, String operator);

    void changeDeviceStatus(String deviceId, Integer status, String operator);


    List<Map<String, Object>> batchGetOnlineStatus(List<String> deviceIds);
}
