package org.jeecg.modules.deviceinfo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.deviceinfo.contract.dto.BindingUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceBatchQueryRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceHeartbeatSnapshotRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceListQuery;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOccupancyEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOnlineEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceRegisterWriteRequest;
import org.jeecg.modules.deviceinfo.contract.dto.FirmwareVersionUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.LifecycleUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.deviceinfo.contract.dto.TestFlagUpdateRequest;
import org.jeecg.modules.deviceinfo.entity.DeviceInfo;

import java.util.List;

/**
 * 设备信息基础服务（SSOT）业务接口，一一对应
 * {@link org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient} 的每个方法，
 * 由 {@code org.jeecg.modules.deviceinfo.controller.DeviceInfoController}（api 模块）承载
 * REST 端点后调用；biz 模块不直接依赖 api 模块，故此处不写可解析的 {@code @link}。
 */
public interface IDeviceInfoService extends IService<DeviceInfo> {

    DeviceInfoDTO getDevice(String deviceId);

    DeviceInfoDTO getDeviceByCode(String deviceCode);

    List<DeviceInfoDTO> batchQuery(DeviceBatchQueryRequest request);

    PageResult<DeviceInfoDTO> list(DeviceListQuery query);

    void register(DeviceRegisterWriteRequest request);

    void reportOnlineEvent(DeviceOnlineEventRequest request);

    void reportOccupancyEvent(DeviceOccupancyEventRequest request);

    void reportHeartbeatSnapshot(DeviceHeartbeatSnapshotRequest request);

    void updateFirmwareVersion(String deviceId, FirmwareVersionUpdateRequest request);

    void updateTestFlag(String deviceId, TestFlagUpdateRequest request);

    void updateBinding(String deviceId, BindingUpdateRequest request);

    void updateLifecycle(String deviceId, LifecycleUpdateRequest request);
}
