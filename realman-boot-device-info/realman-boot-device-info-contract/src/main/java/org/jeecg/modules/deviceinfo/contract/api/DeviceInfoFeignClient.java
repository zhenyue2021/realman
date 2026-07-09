package org.jeecg.modules.deviceinfo.contract.api;

import jakarta.validation.Valid;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.ServiceNameConstants;
import org.jeecg.modules.deviceinfo.contract.api.fallback.DeviceInfoFeignFallbackFactory;
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
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 设备信息基础服务（SSOT，{@code realman-device-info}）对内 Feign 契约。
 *
 * <p>全部为内部调用，不经过对外 Gateway；对齐设备基座详细设计 2.2 节的接口清单与
 * 平台能力清单（{@code docs/design/capability-catalog.md}）第三章。只读接口面向全平台
 * 业务应用开放；写接口按能力清单标注的调用方限定（见各方法注释）。
 *
 * <p>本模块目前只有 contract，尚无 biz 实现（Phase 0）；{@code @ConditionalOnMissingClass}
 * 用于后续 Phase 2 落地 biz 实现类后自动让位，避免同进程内重复实例化。
 */
@FeignClient(
        contextId = "deviceInfoFeignClient",
        value = ServiceNameConstants.SERVICE_DEVICE_INFO,
        path = "${realman.device-info.context-path:/realman-device-info}",
        fallbackFactory = DeviceInfoFeignFallbackFactory.class
)
public interface DeviceInfoFeignClient {

    /** 单设备完整信息。调用方：GLN / 数据处理 / OTA / 状态监控 / 任务规划。 */
    @GetMapping("/internal/device-info/{deviceId}")
    Result<DeviceInfoDTO> getDevice(@PathVariable("deviceId") String deviceId);

    /** 按设备码（SN）查询。调用方：OTA（{@code by_code} 升级场景）。 */
    @GetMapping("/internal/device-info/by-code/{deviceCode}")
    Result<DeviceInfoDTO> getDeviceByCode(@PathVariable("deviceCode") String deviceCode);

    /** 批量查询。调用方：OTA / 任务规划（批量升级选型、版本矩阵）。 */
    @PostMapping("/internal/device-info/batch-query")
    Result<List<DeviceInfoDTO>> batchQuery(@RequestBody @Valid DeviceBatchQueryRequest request);

    /** 分页/条件查询。调用方：设备管理业务平台（台账页面的数据来源）。 */
    @GetMapping("/internal/device-info/list")
    Result<PageResult<DeviceInfoDTO>> list(@SpringQueryMap DeviceListQuery query);

    /** 注册写入。调用方：设备管理业务平台，注册成功后调用。 */
    @PostMapping("/internal/device-info/register")
    Result<Void> register(@RequestBody @Valid DeviceRegisterWriteRequest request);

    /** 在线/离线事件同步。调用方：设备通信中台。 */
    @PostMapping("/internal/device-info/online-event")
    Result<Void> reportOnlineEvent(@RequestBody @Valid DeviceOnlineEventRequest request);

    /** 四态占用同步。调用方：设备通信中台（遥操/自主控制状态变化时触发）。 */
    @PostMapping("/internal/device-info/occupancy-event")
    Result<Void> reportOccupancyEvent(@RequestBody @Valid DeviceOccupancyEventRequest request);

    /** 心跳快照同步。调用方：设备通信中台。 */
    @PostMapping("/internal/device-info/heartbeat-snapshot")
    Result<Void> reportHeartbeatSnapshot(@RequestBody @Valid DeviceHeartbeatSnapshotRequest request);

    /** 固件版本回写。调用方：OTA 平台，升级成功后调用。 */
    @PutMapping("/internal/device-info/{deviceId}/firmware-version")
    Result<Void> updateFirmwareVersion(@PathVariable("deviceId") String deviceId,
                                        @RequestBody @Valid FirmwareVersionUpdateRequest request);

    /** 测试标记同步。调用方：设备管理业务平台。 */
    @PutMapping("/internal/device-info/{deviceId}/test-flag")
    Result<Void> updateTestFlag(@PathVariable("deviceId") String deviceId,
                                 @RequestBody @Valid TestFlagUpdateRequest request);

    /** 绑定关系快照同步。调用方：设备管理业务平台。 */
    @PutMapping("/internal/device-info/{deviceId}/binding")
    Result<Void> updateBinding(@PathVariable("deviceId") String deviceId,
                                @RequestBody @Valid BindingUpdateRequest request);

    /** 生命周期阶段变更。调用方：设备管理业务平台。 */
    @PutMapping("/internal/device-info/{deviceId}/lifecycle")
    Result<Void> updateLifecycle(@PathVariable("deviceId") String deviceId,
                                  @RequestBody @Valid LifecycleUpdateRequest request);
}
