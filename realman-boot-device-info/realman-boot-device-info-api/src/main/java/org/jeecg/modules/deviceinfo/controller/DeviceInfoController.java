package org.jeecg.modules.deviceinfo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
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
import org.jeecg.modules.deviceinfo.service.IDeviceInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备信息基础服务（SSOT）内部接口。路径与
 * {@link org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient} 逐一对应，
 * 不经过对外 Gateway，仅供集群内其他服务通过 Feign 调用。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "设备信息基础服务", description = "SSOT 只读查询 + 各写入方同步接口")
public class DeviceInfoController {

    private final IDeviceInfoService deviceInfoService;

    @GetMapping("/internal/device-info/{deviceId}")
    @Operation(summary = "单设备完整信息")
    public Result<DeviceInfoDTO> getDevice(@PathVariable String deviceId) {
        return Result.ok(deviceInfoService.getDevice(deviceId));
    }

    @GetMapping("/internal/device-info/by-code/{deviceCode}")
    @Operation(summary = "按设备码查询")
    public Result<DeviceInfoDTO> getDeviceByCode(@PathVariable String deviceCode) {
        return Result.ok(deviceInfoService.getDeviceByCode(deviceCode));
    }

    @PostMapping("/internal/device-info/batch-query")
    @Operation(summary = "批量查询")
    public Result<List<DeviceInfoDTO>> batchQuery(@RequestBody @Valid DeviceBatchQueryRequest request) {
        return Result.ok(deviceInfoService.batchQuery(request));
    }

    @GetMapping("/internal/device-info/list")
    @Operation(summary = "分页/条件查询")
    public Result<PageResult<DeviceInfoDTO>> list(DeviceListQuery query) {
        return Result.ok(deviceInfoService.list(query));
    }

    @PostMapping("/internal/device-info/register")
    @Operation(summary = "注册写入")
    public Result<Void> register(@RequestBody @Valid DeviceRegisterWriteRequest request) {
        deviceInfoService.register(request);
        return Result.ok();
    }

    @PostMapping("/internal/device-info/online-event")
    @Operation(summary = "在线/离线事件同步")
    public Result<Void> reportOnlineEvent(@RequestBody @Valid DeviceOnlineEventRequest request) {
        deviceInfoService.reportOnlineEvent(request);
        return Result.ok();
    }

    @PostMapping("/internal/device-info/occupancy-event")
    @Operation(summary = "四态占用同步")
    public Result<Void> reportOccupancyEvent(@RequestBody @Valid DeviceOccupancyEventRequest request) {
        deviceInfoService.reportOccupancyEvent(request);
        return Result.ok();
    }

    @PostMapping("/internal/device-info/heartbeat-snapshot")
    @Operation(summary = "心跳快照同步")
    public Result<Void> reportHeartbeatSnapshot(@RequestBody @Valid DeviceHeartbeatSnapshotRequest request) {
        deviceInfoService.reportHeartbeatSnapshot(request);
        return Result.ok();
    }

    @PutMapping("/internal/device-info/{deviceId}/firmware-version")
    @Operation(summary = "固件版本回写")
    public Result<Void> updateFirmwareVersion(@PathVariable String deviceId,
                                               @RequestBody @Valid FirmwareVersionUpdateRequest request) {
        deviceInfoService.updateFirmwareVersion(deviceId, request);
        return Result.ok();
    }

    @PutMapping("/internal/device-info/{deviceId}/test-flag")
    @Operation(summary = "测试标记同步")
    public Result<Void> updateTestFlag(@PathVariable String deviceId,
                                        @RequestBody @Valid TestFlagUpdateRequest request) {
        deviceInfoService.updateTestFlag(deviceId, request);
        return Result.ok();
    }

    @PutMapping("/internal/device-info/{deviceId}/binding")
    @Operation(summary = "绑定关系快照同步")
    public Result<Void> updateBinding(@PathVariable String deviceId,
                                       @RequestBody @Valid BindingUpdateRequest request) {
        deviceInfoService.updateBinding(deviceId, request);
        return Result.ok();
    }

    @PutMapping("/internal/device-info/{deviceId}/lifecycle")
    @Operation(summary = "生命周期阶段变更")
    public Result<Void> updateLifecycle(@PathVariable String deviceId,
                                         @RequestBody @Valid LifecycleUpdateRequest request) {
        deviceInfoService.updateLifecycle(deviceId, request);
        return Result.ok();
    }
}
