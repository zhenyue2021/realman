package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.dto.DeviceAddDTO;
import org.jeecg.modules.device.dto.DeviceRestartDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 设备管理接口（供运维平台调用）
 *
 * 鉴权说明：
 *   设备端只有 deviceCode，MQTT 密码 = MD5(deviceCode)，无需任何其他凭证。
 *   平台在此处提供 /secret/preview 接口，方便运维人员查看设备连接密码
 *   （仅用于调试和设备配置核查，生产环境可按需关闭）
 */
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
@Tag(name = "设备管理", description = "设备注册/参数配置/实时监控/远程重启/密钥管理")
public class DeviceController {

    private final IIotDeviceService deviceService;

    /** 新增设备（设备端只需 deviceCode，平台自动生成deviceSecret=MQTT密码=MD5(deviceCode)） */
    @PostMapping("/add")
    @Operation(summary = "新增设备")
    public ApiResult<IotDevice> addDevice(@Valid @RequestBody DeviceAddDTO dto) {
        IotDevice d = new IotDevice();
        d.setDeviceCode(dto.getDeviceCode()); d.setDeviceName(dto.getDeviceName());
        d.setDeviceType(dto.getDeviceType()); d.setProductId(dto.getProductId());
        d.setDeviceModel(dto.getDeviceModel()); d.setSerialNumber(dto.getSerialNumber());
        d.setDescription(dto.getDescription());
        return ApiResult.ok(deviceService.addDevice(d), "设备添加成功，请通过安全渠道将deviceSecret下发至设备端");
    }

    /** 分页查询设备列表 */
    @GetMapping("/list")
    @Operation(summary = "分页查询设备列表")
    public ApiResult<IPage<IotDevice>> list(
            @RequestParam(defaultValue="1") Integer pageNo,
            @RequestParam(defaultValue="10") Integer pageSize,
            @RequestParam(required=false) String deviceName,
            @RequestParam(required=false) Integer deviceType,
            @RequestParam(required=false) Integer status,
            @RequestParam(required=false) String productId) {
        return ApiResult.ok(deviceService.queryDevicePage(
                new Page<>(pageNo, pageSize), deviceName, deviceType, status, productId));
    }

    /** 查询设备详情 */
    @GetMapping("/{deviceId}")
    @Operation(summary = "查询设备详情")
    public ApiResult<IotDevice> detail(@PathVariable String deviceId) {
        return ApiResult.ok(deviceService.getById(deviceId));
    }

    /** 设置参数并同步到设备（在线立即推送，离线待上线后同步） */
    @PostMapping("/{deviceId}/config/sync")
    @Operation(summary = "设置并同步设备参数")
    public ApiResult<Void> syncConfig(@PathVariable String deviceId,
            @RequestBody Map<String, Object> params) {
        deviceService.setAndSyncConfig(deviceId, params);
        return ApiResult.ok(null, "参数已保存，在线设备将立即收到加密配置推送");
    }

    /** 获取实时监控状态（优先Redis，降级DB） */
    @GetMapping("/{deviceId}/monitor")
    @Operation(summary = "获取设备实时监控状态")
    public ApiResult<Map<String, Object>> monitor(@PathVariable String deviceId) {
        return ApiResult.ok(deviceService.getDeviceMonitorStatus(deviceId));
    }

    /** 远程重启（通过MQTT向设备发送AES加密重启指令） */
    @PostMapping("/{deviceId}/restart")
    @Operation(summary = "远程重启设备")
    public ApiResult<Void> restart(@PathVariable String deviceId,
            @RequestBody DeviceRestartDTO dto) {
        deviceService.remoteRestart(deviceId, dto.getReason(), dto.getOperator());
        return ApiResult.ok(null, "重启指令已发送，等待设备确认");
    }

    /** 禁用/启用设备（禁用时立即清除密钥缓存，EMQX将拒绝该设备连接） */
    @PutMapping("/{deviceId}/status/{status}")
    @Operation(summary = "禁用或启用设备")
    public ApiResult<Void> changeStatus(@PathVariable String deviceId,
            @PathVariable Integer status,
            @RequestParam(defaultValue="system") String operator) {
        deviceService.changeDeviceStatus(deviceId, status, operator);
        return ApiResult.ok(null);
    }


    /** 批量查询在线状态 */
    @PostMapping("/batch/online-status")
    @Operation(summary = "批量查询设备在线状态")
    public ApiResult<List<Map<String, Object>>> batchOnline(@RequestBody List<String> deviceIds) {
        return ApiResult.ok(deviceService.batchGetOnlineStatus(deviceIds));
    }
}
