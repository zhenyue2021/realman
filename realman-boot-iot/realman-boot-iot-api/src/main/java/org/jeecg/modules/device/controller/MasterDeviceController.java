package org.jeecg.modules.device.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
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
import org.jeecg.modules.device.dto.MasterControlParamsDTO;
import org.jeecg.modules.device.dto.MasterLoginDTO;
import org.jeecg.modules.device.dto.DeviceAddDTO;
import org.jeecg.modules.device.dto.OperationRecordQueryDTO;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.MasterDevicePageItemDTO;
import org.jeecg.modules.device.dto.DeviceRestartDTO;
import org.jeecg.modules.device.dto.TeleopStartDTO;
import org.jeecg.modules.device.dto.TeleopStopDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.dto.EmergencyStopDTO;
import org.jeecg.modules.device.api.MasterDeviceApiService;
import org.jeecg.modules.device.entity.MasterOperationRecord;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.service.IMasterLoginResolveService;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.service.IMasterUsageStatusService;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.util.DeviceExcelExportUtil;
import org.jeecg.modules.device.vo.ApiResult;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.jeecg.modules.device.vo.MasterLoginResolveVO;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 主控端管理接口（device_type=2）
 */
@RestController
@RequestMapping("/api/teleop")
@RequiredArgsConstructor
@Tag(name = "主控端管理", description = "主控设备注册/参数配置/实时监控/远程重启/导出/逻辑删除/登录记录")
@Slf4j
public class MasterDeviceController {

    private static final int DEVICE_TYPE_CONTROLLER = 2;
    private static final int DEVICE_TYPE_ROBOT = 1;

    private final IIotDeviceService deviceService;
    private final IMasterLoginResolveService loginResolveService;
    private final IMasterOperationRecordService operationRecordService;
    private final IMasterUsageStatusService usageStatusService;
    private final MasterDeviceApiService masterDeviceApiService;

    /** 新增主控设备 */
    @PostMapping("/add")
    @Operation(summary = "新增主控设备")
    public ApiResult<IotDevice> add(@Valid @RequestBody DeviceAddDTO dto) {
        IotDevice d = new IotDevice();
        d.setDeviceCode(dto.getDeviceCode());
        d.setDeviceName(dto.getDeviceName());
        d.setDeviceType(DEVICE_TYPE_CONTROLLER);
        d.setProductId(dto.getProductId());
        d.setDeviceModel(dto.getDeviceModel());
        d.setSerialNumber(dto.getSerialNumber());
        d.setMacAddress(dto.getMacAddress());
        d.setDescription(dto.getDescription());
        return ApiResult.ok(deviceService.addDevice(d), "设备添加成功");
    }

    /** 分页查询主控设备列表 */
    @PostMapping("/list")
    @Operation(summary = "分页查询主控设备列表")
    public ApiResult<IPage<MasterDevicePageItemDTO>> list(HttpServletRequest request,
                                           @RequestBody DeviceRequestDTO requestDTO) {
        fillAuthContext(request, requestDTO);
        requestDTO.setDeviceType(DEVICE_TYPE_CONTROLLER);
        int pageNo = Objects.nonNull(requestDTO.getPageNo()) ? requestDTO.getPageNo() : 1;
        int pageSize = Objects.nonNull(requestDTO.getPageSize()) ? requestDTO.getPageSize() : 10;
        return ApiResult.ok(masterDeviceApiService.pageControllers(new Page<>(pageNo, pageSize), requestDTO));
    }


    /** 查询主控设备详情（聚合） */
    @GetMapping("/{controllerId}/detail")
    @Operation(summary = "查询主控设备详情（聚合）")
    public ApiResult<DeviceDetailVO> detailAgg(@PathVariable String controllerId) {
        DeviceDetailVO vo = deviceService.getDeviceDetail(controllerId);
        if (vo != null && vo.getDevice() != null && !Objects.equals(vo.getDevice().getDeviceType(), DEVICE_TYPE_CONTROLLER)) {
            throw new RuntimeException("设备类型不匹配：该ID不是主控设备");
        }
        return ApiResult.ok(vo);
    }

    /** 设置参数并同步到主控设备 */
    @PostMapping("/{controllerId}/config/sync")
    @Operation(summary = "设置并同步主控设备参数")
    public ApiResult<Void> syncConfig(@PathVariable String controllerId,
                                      @RequestBody Map<String, Object> params) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.setAndSyncConfig(controllerId, params);
        return ApiResult.ok(null, "参数已保存，在线设备将立即收到加密配置推送");
    }

    /** 获取实时监控状态 */
    @GetMapping("/{controllerId}/monitor")
    @Operation(summary = "获取主控设备实时监控状态")
    public ApiResult<Map<String, Object>> monitor(@PathVariable String controllerId) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        return ApiResult.ok(deviceService.getDeviceMonitorStatus(controllerId));
    }

    /** 远程重启主控 */
    @PostMapping("/{controllerId}/restart")
    @Operation(summary = "远程重启主控设备")
    public ApiResult<Void> restart(@PathVariable String controllerId,
                                   @RequestBody DeviceRestartDTO dto) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.remoteRestart(controllerId, dto.getReason(), dto.getOperator());
        return ApiResult.ok(null, "重启指令已发送，等待设备确认");
    }

    /** 紧急停机（通过MQTT向设备发送AES加密指令，设备需上行 ACK 确认） */
    @PostMapping("/{controllerId}/emergency-stop")
    @Operation(summary = "紧急停机主控设备")
    public ApiResult<Void> emergencyStop(@PathVariable String controllerId,
                                         @RequestBody EmergencyStopDTO dto) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.emergencyStop(controllerId, dto.getReason(), dto.getOperator());
        return ApiResult.ok(null, "紧急停机指令已发送，等待设备确认");
    }

    /**
     * 设置主控端力反馈 + 运动与安全参数（一次性提交）
     *
     * <p>对应设备侧 Topic：
     * master/{controllerCode}/command/force-feedback
     * master/{controllerCode}/command/sport-speed
     */
    @PostMapping("/control-params")
    @Operation(summary = "设置主控设备力反馈及运动参数")
    public ApiResult<Void> setControlParams(@RequestBody MasterControlParamsDTO dto) {
        String controllerId = dto.getControllerId();
        if (StrUtil.isEmpty(controllerId)) {
            return ApiResult.fail("参数错误：controllerId 不能为空");
        }
        IotDevice controller = ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        org.jeecg.modules.device.service.impl.IotDeviceServiceImpl impl =
                (org.jeecg.modules.device.service.impl.IotDeviceServiceImpl) deviceService;
        impl.sendMasterForceFeedbackCommand(controller, dto.getArmLevel(), dto.getGripperLevel(), dto.getOperator(), dto.getArmLevelConfigType(), dto.getGripperLevelConfigType());
        impl.sendMasterSportSpeedCommand(controller, dto.getMoveSpeedLevel(), dto.getLiftSpeedLevel(), dto.getOperator(), dto.getMoveSpeedLevelConfigType(), dto.getLiftSpeedLevelConfigType());
        return ApiResult.ok(null, "参数已下发，等待主控设备确认");
    }

    /** 编辑主控设备 */
    @PutMapping("/{controllerId}")
    @Operation(summary = "编辑主控设备")
    public ApiResult<Void> update(@PathVariable String controllerId,
                                  @RequestBody DeviceUpdateDTO dto) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.updateDevice(controllerId, dto);
        return ApiResult.ok(null, "更新成功");
    }

    /** 删除主控设备（逻辑删除） */
    @DeleteMapping("/{controllerId}")
    @Operation(summary = "删除主控设备（逻辑删除）")
    public ApiResult<Void> delete(@PathVariable String controllerId) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.removeById(controllerId);
        return ApiResult.ok(null, "已删除");
    }

    /** 禁用/启用主控设备 */
    @PutMapping("/{controllerId}/status/{status}")
    @Operation(summary = "禁用或启用主控设备")
    public ApiResult<Void> changeStatus(@PathVariable String controllerId,
                                        @PathVariable Integer status,
                                        @RequestParam(defaultValue = "system") String operator) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.changeDeviceStatus(controllerId, status, operator);
        return ApiResult.ok(null);
    }

    /** 批量查询在线状态 */
    @PostMapping("/batch/online-status")
    @Operation(summary = "批量查询主控设备在线状态")
    public ApiResult<List<Map<String, Object>>> batchOnline(@RequestBody List<String> controllerIds) {
        return ApiResult.ok(deviceService.batchGetOnlineStatus(controllerIds));
    }

    /** 主控端登录记录 */
    @PostMapping("/login")
    @Operation(summary = "主控端登录记录")
    public ApiResult<Void> recordControllerLogin(@RequestBody MasterLoginDTO dto) {
        loginResolveService.recordLogin(dto);
        return ApiResult.ok(null, "登录记录已保存");
    }

    /**
     * 登录后同步解析“当前登录的是哪台设备/关联机器人”
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验：主控设备存在且为 device_type=2</li>
     *   <li>校验：当前登录用户对该主控存在有效授权（iot_device_auth）</li>
     *   <li>校验：响应中的机器人也在该授权绑定范围内</li>
     *   <li>写入登录日志（iot_controller_login_log）</li>
     *   <li>返回主控信息 + 当前机器人信息 + 可用机器人列表 + 工单信息</li>
     * </ol>
     */
    @PostMapping("/login/resolve")
    @Operation(summary = "登录后同步解析当前主控与机器人")
    public ApiResult<MasterLoginResolveVO> resolveLogin(HttpServletRequest request,
                                                        @RequestBody MasterLoginDTO dto) {
        MasterLoginResolveVO vo = loginResolveService.resolve(request, dto);
        return ApiResult.ok(vo);
    }

    /** 开始遥操（通知主控关联目标机器人，不等待ACK） */
    @PostMapping("/{controllerId}/teleop/start")
    @Operation(summary = "主控关联目标机器人，并获取视频流")
    public ApiResult<List<DeviceCameraStreamVO>> startTeleop(@PathVariable String controllerId,
                                       @RequestBody TeleopStartDTO dto) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        List<DeviceCameraStreamVO> cameraStreams = deviceService.startTeleop(controllerId, dto.getDeviceId(), dto.getOperator());
        return ApiResult.ok(cameraStreams, "开始遥操指令已下发，并尝试获取视频流");
    }

    /** 停止遥操（通知主控与机器人，不等待ACK） */
    @PostMapping("/{controllerId}/teleop/stop")
    @Operation(summary = "停止遥操")
    public ApiResult<Void> stopTeleop(@PathVariable String controllerId,
                                      @RequestBody TeleopStopDTO dto) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.stopTeleop(controllerId, dto.getDeviceId(), dto.getDeviceCode(), dto.getOperator());
        return ApiResult.ok(null, "停止遥操指令已下发");
    }

    /** 操作记录分页（遥操员使用主控操控机器人完成工单的时间） */
    @PostMapping("/operation-record/page")
    @Operation(summary = "操作记录分页")
    public ApiResult<IPage<MasterOperationRecord>> operationRecordPage(@RequestBody OperationRecordQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 10;
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MasterOperationRecord> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize);
        IPage<MasterOperationRecord> result = operationRecordService.pageRecords(page,
                query.getControllerId(), query.getControllerCode(), query.getRobotId(),
                query.getStartTimeFrom(), query.getStartTimeTo());
        return ApiResult.ok(result);
    }

    /** 操作记录导出 Excel */
    @PostMapping("/operation-record/export")
    @Operation(summary = "操作记录导出")
    public ResponseEntity<byte[]> operationRecordExport(@RequestBody OperationRecordQueryDTO query) {
        java.util.List<MasterOperationRecord> list = operationRecordService.listForExport(
                query.getControllerId(), query.getControllerCode(), query.getRobotId(),
                query.getStartTimeFrom(), query.getStartTimeTo());
        byte[] bytes;
        try {
            bytes = DeviceExcelExportUtil.exportOperationRecords(list);
        } catch (Exception e) {
            throw new RuntimeException("导出失败", e);
        }
        String filename = "operation_record_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(filename))
                .body(bytes);
    }

    /** 使用状态：最近登录时间、最近一次遥操开始时间、当前设备、可使用的机器人 */
    @GetMapping("/usage-status/{controllerCode}")
    @Operation(summary = "主控使用状态")
    public ApiResult<UsageStatusVO> usageStatus(@PathVariable String controllerCode) {
        UsageStatusVO vo = usageStatusService.getUsageStatusByCode(controllerCode);
        if (vo == null) {
            throw new RuntimeException("主控设备不存在或非主控设备: " + controllerCode);
        }
        return ApiResult.ok(vo);
    }

    /** 导出主控设备列表为 Excel（条件与 list 一致，逻辑删除的不导出） */
    @PostMapping("/export")
    @Operation(summary = "导出主控设备列表Excel")
    public ResponseEntity<byte[]> export(HttpServletRequest request, @RequestBody DeviceRequestDTO requestDTO) {
        fillAuthContext(request, requestDTO);
        requestDTO.setDeviceType(DEVICE_TYPE_CONTROLLER);
        byte[] bytes = deviceService.exportDeviceList(requestDTO);
        String filename = "controllers_" + System.currentTimeMillis() + ".xlsx";
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

    private IotDevice ensureDeviceType(String deviceId, int expectType) {
        IotDevice d = deviceService.getById(deviceId);
        if (d == null) {
            throw new RuntimeException("设备不存在: " + deviceId);
        }
        if (!Objects.equals(d.getDeviceType(), expectType)) {
            throw new RuntimeException("设备类型不匹配");
        }
        return d;
    }

    /** 向主控下发运动速度查询指令（设备 ACK 后数据由 MQTT Handler 异步处理） */
    @GetMapping("/{controllerId}/sport-speed")
    @Operation(summary = "查询主控运动速度参数")
    public ApiResult<Void> getSportSpeed(@PathVariable String controllerId) {
        ensureDeviceType(controllerId, DEVICE_TYPE_CONTROLLER);
        deviceService.queryMasterSportSpeed(controllerId);
        deviceService.queryMasterForceFeedback(controllerId);
        return ApiResult.ok(null, "查询指令已下发");
    }

    /**
     * 获取机器人全部摄像头视频流地址
     *
     * <p>调用 {@link IIotDeviceService#getCameraStreams(String, Integer)} 并传入 cameraIndex = null，
     * 由机器人返回所有已配置摄像头的流地址列表。接口为同步调用，最长等待 10 秒。
     */
    @GetMapping("/{deviceId}/camera/stream")
    @Operation(summary = "获取机器人全部摄像头视频流地址")
    public ApiResult<List<DeviceCameraStreamVO>> getCameraStreams(@PathVariable String deviceId) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        return ApiResult.ok(deviceService.getCameraStreams(deviceId, null));
    }

    /**
     * 获取机器人指定路数摄像头视频流地址（单路）
     *
     * <p>前端通过 path 传入 cameraIndex（从 0 开始），平台会校验为非负整数；
     * Service 仍按列表形式返回，Controller 只取第一条作为“当前摄像头”的视频流信息。
     */
    @GetMapping("/{deviceId}/camera/stream/{cameraIndex}")
    @Operation(summary = "获取机器人指定路数摄像头视频流地址")
    public ApiResult<DeviceCameraStreamVO> getCameraStreamByIndex(@PathVariable String deviceId,
                                                                  @PathVariable Integer cameraIndex) {
        ensureDeviceType(deviceId, DEVICE_TYPE_ROBOT);
        if (cameraIndex == null || cameraIndex < 0 || cameraIndex > 3) {
            throw new RuntimeException("cameraIndex 越域 => [0-3]");
        }
        List<DeviceCameraStreamVO> list = deviceService.getCameraStreams(deviceId, cameraIndex);
        DeviceCameraStreamVO vo = list.isEmpty() ? null : list.get(0);
        return ApiResult.ok(vo);
    }
}

