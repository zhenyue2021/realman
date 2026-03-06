package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.IotDeviceOperationLog;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/log")
@RequiredArgsConstructor
@Tag(name = "设备操作日志", description = "设备操作日志查询")
public class DeviceOperationLogController {

    private final IDeviceOperationLogService logService;

    @GetMapping("/device/{deviceId}")
    @Operation(summary = "查询设备操作日志")
    public ApiResult<IPage<IotDeviceOperationLog>> deviceLog(
            @PathVariable String deviceId,
            @RequestParam(defaultValue="1") Integer pageNo,
            @RequestParam(defaultValue="20") Integer pageSize,
            @RequestParam(required=false) String operationType,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResult.ok(logService.queryLogPage(
                new Page<>(pageNo, pageSize), deviceId, operationType, startTime, endTime));
    }

    @GetMapping("/list")
    @Operation(summary = "全局操作日志分页查询")
    public ApiResult<IPage<IotDeviceOperationLog>> allLog(
            @RequestParam(defaultValue="1") Integer pageNo,
            @RequestParam(defaultValue="20") Integer pageSize,
            @RequestParam(required=false) String operationType,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResult.ok(logService.queryLogPage(
                new Page<>(pageNo, pageSize), null, operationType, startTime, endTime));
    }
}
