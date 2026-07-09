package org.jeecg.modules.deviceinfo.contract.api.fallback;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
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

import java.util.List;

/**
 * 设备信息基础服务不可用时的降级实现：全部返回失败 {@link Result}，不抛异常炸调用方。
 * 具体的重试/熔断策略由各消费方按自身业务场景决定，这里只保证"调用链不因下游异常而崩"。
 */
@Slf4j
public class DeviceInfoFeignFallback implements DeviceInfoFeignClient {

    @Setter
    private Throwable cause;

    private <T> Result<T> unavailable(String action) {
        log.error("[device-info] {} 调用失败，服务不可用", action, cause);
        return Result.error("设备信息基础服务暂不可用：" + action);
    }

    @Override
    public Result<DeviceInfoDTO> getDevice(String deviceId) {
        return unavailable("getDevice");
    }

    @Override
    public Result<DeviceInfoDTO> getDeviceByCode(String deviceCode) {
        return unavailable("getDeviceByCode");
    }

    @Override
    public Result<List<DeviceInfoDTO>> batchQuery(DeviceBatchQueryRequest request) {
        return unavailable("batchQuery");
    }

    @Override
    public Result<PageResult<DeviceInfoDTO>> list(DeviceListQuery query) {
        return unavailable("list");
    }

    @Override
    public Result<Void> register(DeviceRegisterWriteRequest request) {
        return unavailable("register");
    }

    @Override
    public Result<Void> reportOnlineEvent(DeviceOnlineEventRequest request) {
        return unavailable("reportOnlineEvent");
    }

    @Override
    public Result<Void> reportOccupancyEvent(DeviceOccupancyEventRequest request) {
        return unavailable("reportOccupancyEvent");
    }

    @Override
    public Result<Void> reportHeartbeatSnapshot(DeviceHeartbeatSnapshotRequest request) {
        return unavailable("reportHeartbeatSnapshot");
    }

    @Override
    public Result<Void> updateFirmwareVersion(String deviceId, FirmwareVersionUpdateRequest request) {
        return unavailable("updateFirmwareVersion");
    }

    @Override
    public Result<Void> updateTestFlag(String deviceId, TestFlagUpdateRequest request) {
        return unavailable("updateTestFlag");
    }

    @Override
    public Result<Void> updateBinding(String deviceId, BindingUpdateRequest request) {
        return unavailable("updateBinding");
    }

    @Override
    public Result<Void> updateLifecycle(String deviceId, LifecycleUpdateRequest request) {
        return unavailable("updateLifecycle");
    }
}
