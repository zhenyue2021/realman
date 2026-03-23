package org.jeecg.modules.device.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.ContentDispositionUtil;
import org.jeecg.modules.device.api.RobotDeviceApiService;
import org.jeecg.modules.device.dto.DeviceAddDTO;
import org.jeecg.modules.device.dto.EmergencyStopDTO;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceRestartDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.dto.RobotDevicePageItemDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.vo.ApiResult;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.jeecg.modules.device.vo.RobotDeviceDetailVO;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 机器人设备管理接口（device_type=1）
 */
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
@Tag(name = "设备管理（机器人）", description = "机器人设备注册/参数配置/实时监控/远程重启/导出/逻辑删除")
@Slf4j
public class RobotDeviceController {

    private static final int DEVICE_TYPE_ROBOT = 1;

    private final IIotDeviceService deviceService;
    private final RobotDeviceApiService robotDeviceApiService;
    private final DeviceWebSocketServer webSocketServer;

    /** 新增机器人设备 */
    @PostMapping("/add")
    @Operation(summary = "新增机器人设备")
    public ApiResult<RobotDevicePageItemDTO> addDevice(@Valid @RequestBody DeviceAddDTO dto) {
        IotDevice d = new IotDevice();
        d.setDeviceCode(dto.getDeviceCode());
        d.setDeviceName(dto.getDeviceName());
        d.setDeviceType(DEVICE_TYPE_ROBOT);
        d.setProductId(dto.getProductId());
        d.setDeviceModel(dto.getDeviceModel());
        d.setSerialNumber(dto.getSerialNumber());
        d.setMacAddress(dto.getMacAddress());
        d.setDescription(dto.getDescription());
        IotDevice saved = deviceService.addDevice(d);
        return ApiResult.ok(robotDeviceApiService.toPageItem(saved), "设备添加成功");
    }

    /** 分页查询机器人设备列表 */
    @PostMapping("/list")
    @Operation(summary = "分页查询机器人设备列表")
    public ApiResult<IPage<RobotDevicePageItemDTO>> list(HttpServletRequest request,
                                                         @RequestBody DeviceRequestDTO requestDTO) {
        fillAuthContext(request, requestDTO);
        requestDTO.setDeviceType(DEVICE_TYPE_ROBOT);
        int pageNo = Objects.nonNull(requestDTO.getPageNo()) ? requestDTO.getPageNo() : 1;
        int pageSize = Objects.nonNull(requestDTO.getPageSize()) ? requestDTO.getPageSize() : 10;
        return ApiResult.ok(robotDeviceApiService.pageRobots(new Page<>(pageNo, pageSize), requestDTO));
    }

    /** 查询机器人设备详情 */
    @GetMapping("/{deviceId}")
    @Operation(summary = "查询机器人设备详情")
    public ApiResult<RobotDevicePageItemDTO> detail(@PathVariable String deviceId) {
        return ApiResult.ok(robotDeviceApiService.getRobotDeviceView(deviceId));
    }

    /** 查询机器人设备详情（聚合） */
    @GetMapping("/{deviceId}/detail")
    @Operation(summary = "查询机器人设备详情（聚合）")
    public ApiResult<DeviceDetailVO> detailAgg(@PathVariable String deviceId) {
        DeviceDetailVO vo = deviceService.getDeviceDetail(deviceId);
        if (vo != null && vo.getDevice() != null && !Objects.equals(vo.getDevice().getDeviceType(), DEVICE_TYPE_ROBOT)) {
            throw new RuntimeException("设备类型不匹配：该ID不是机器人设备");
        }
        return ApiResult.ok(vo);
    }

    /** 设置参数并同步到机器人设备（在线立即推送，离线待上线后同步） */
    @PostMapping("/{deviceId}/config/sync")
    @Operation(summary = "设置并同步机器人设备参数")
    public ApiResult<Void> syncConfig(@PathVariable String deviceId,
                                      @RequestBody Map<String, Object> params) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        deviceService.setAndSyncConfig(deviceId, params);
        return ApiResult.ok(null, "参数已保存，在线设备将立即收到加密配置推送");
    }

    /** 获取实时监控状态（优先Redis，降级DB） */
    @GetMapping("/{deviceId}/monitor")
    @Operation(summary = "获取机器人设备实时监控状态")
    public ApiResult<Map<String, Object>> monitor(@PathVariable String deviceId) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        return ApiResult.ok(deviceService.getDeviceMonitorStatus(deviceId));
    }

    /** 远程重启 */
    @PostMapping("/{deviceId}/restart")
    @Operation(summary = "远程重启机器人设备")
    public ApiResult<Void> restart(@PathVariable String deviceId,
                                   @RequestBody DeviceRestartDTO dto) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        deviceService.remoteRestart(deviceId, dto.getReason(), dto.getOperator());
        return ApiResult.ok(null, "重启指令已发送，等待设备确认");
    }

    /** 紧急停机（通过MQTT向设备发送AES加密指令，设备需上行 ACK 确认） */
    @PostMapping("/{deviceId}/emergency-stop")
    @Operation(summary = "紧急停机机器人设备")
    public ApiResult<Void> emergencyStop(@PathVariable String deviceId,
                                         @RequestBody EmergencyStopDTO dto) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        deviceService.emergencyStop(deviceId, dto.getReason(), dto.getOperator());
        return ApiResult.ok(null, "紧急停机指令已发送，等待设备确认");
    }

    /** 编辑机器人设备 */
    @PutMapping("/{deviceId}")
    @Operation(summary = "编辑机器人设备")
    public ApiResult<Void> update(@PathVariable String deviceId,
                                  @RequestBody DeviceUpdateDTO dto) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        deviceService.updateDevice(deviceId, dto);
        return ApiResult.ok(null, "更新成功");
    }

    /** 删除机器人设备（逻辑删除） */
    @DeleteMapping("/{deviceId}")
    @Operation(summary = "删除机器人设备（逻辑删除）")
    public ApiResult<Void> delete(@PathVariable String deviceId) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        deviceService.removeById(deviceId);
        return ApiResult.ok(null, "已删除");
    }

    /** 禁用/启用机器人设备 */
    @PutMapping("/{deviceId}/status/{status}")
    @Operation(summary = "禁用或启用机器人设备")
    public ApiResult<Void> changeStatus(@PathVariable String deviceId,
                                        @PathVariable Integer status,
                                        @RequestParam(defaultValue = "system") String operator) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        deviceService.changeDeviceStatus(deviceId, status, operator);
        return ApiResult.ok(null);
    }

    /** 批量查询在线状态 */
    @PostMapping("/batch/online-status")
    @Operation(summary = "批量查询机器人设备在线状态")
    public ApiResult<List<Map<String, Object>>> batchOnline(@RequestBody List<String> deviceIds) {
        return ApiResult.ok(deviceService.batchGetOnlineStatus(deviceIds));
    }

    /** 导出机器人设备列表为 Excel（条件与 list 一致，逻辑删除的不导出） */
    @PostMapping("/export")
    @Operation(summary = "导出机器人设备列表Excel")
    public ResponseEntity<byte[]> export(HttpServletRequest request, @RequestBody DeviceRequestDTO requestDTO) {
        fillAuthContext(request, requestDTO);
        requestDTO.setDeviceType(DEVICE_TYPE_ROBOT);
        byte[] bytes = deviceService.exportDeviceList(requestDTO);
        String filename = "robot_devices_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(filename))
                .body(bytes);
    }

    private void fillAuthContext(HttpServletRequest request, DeviceRequestDTO requestDTO) {
        String username = null;
        try {
            username = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        requestDTO.setCurrentUsername(username);
        requestDTO.setCurrentTenantId(request.getHeader("x-tenant-id"));
        requestDTO.setSuperAdmin("admin".equalsIgnoreCase(username));
    }

    private void ensureDeviceType(String deviceId, int expectType) {
        IotDevice d = deviceService.getById(deviceId);
        if (d == null) throw new RuntimeException("设备不存在: " + deviceId);
        if (!Objects.equals(d.getDeviceType(), expectType)) throw new RuntimeException("设备类型不匹配");
    }

    @PostMapping("/mock/device-status")
    public void mockDeviceStatusJob() {
        List<IotDevice> devices = deviceService.list(Wrappers.lambdaQuery(IotDevice.class));

        for (IotDevice device : devices) {
            webSocketServer.pushDeviceStatus(device.getDeviceCode(), JSONUtil.toJsonStr(device));
        }

        log.info("[mockDeviceStatusJob] 模拟推送数据 {} 条（设备数={}）", devices.size(), devices.size());
    }
}
