package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.dto.OtaTaskDTO;
import org.jeecg.modules.device.entity.IotOtaFirmware;
import org.jeecg.modules.device.entity.IotOtaUpgradeTask;
import org.jeecg.modules.device.service.IIotOtaService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/ota")
@RequiredArgsConstructor
@Tag(name = "OTA固件升级", description = "固件分片上传(断点续传)/升级任务管理")
public class OtaController {

    private final IIotOtaService otaService;

    /** 分片上传固件（支持断点续传，uploadId首次为空则自动生成） */
    @PostMapping("/firmware/upload/chunk")
    @Operation(summary = "固件分片上传（断点续传）")
    public ApiResult<String> uploadChunk(
            @RequestParam MultipartFile file,
            @RequestParam(required=false) String uploadId,
            @RequestParam Integer chunkIndex,
            @RequestParam Integer totalChunks) {
        return ApiResult.ok(otaService.uploadFirmwareChunk(file, uploadId, chunkIndex, totalChunks));
    }

    /** 查询已上传分片（断点续传恢复时调用） */
    @GetMapping("/firmware/upload/chunks")
    @Operation(summary = "查询已上传分片列表（断点续传恢复）")
    public ApiResult<List<Integer>> getChunks(@RequestParam String uploadId) {
        return ApiResult.ok(otaService.getUploadedChunks(uploadId));
    }

    /** 合并分片并发布固件 */
    @PostMapping("/firmware/upload/merge")
    @Operation(summary = "合并分片并发布固件到MinIO")
    public ApiResult<IotOtaFirmware> merge(
            @RequestParam String uploadId,
            @RequestParam String firmwareName,
            @RequestParam String version,
            @RequestParam String productId,
            @RequestParam(required=false) String description) {
        return ApiResult.ok(otaService.mergeAndPublish(uploadId, firmwareName, version, productId, description));
    }

    /** 查询固件列表 */
    @GetMapping("/firmware/list")
    @Operation(summary = "查询固件列表")
    public ApiResult<IPage<IotOtaFirmware>> firmwareList(
            @RequestParam(defaultValue="1") Integer pageNo,
            @RequestParam(defaultValue="10") Integer pageSize,
            @RequestParam(required=false) String productId) {
        return ApiResult.ok(otaService.page(new Page<>(pageNo, pageSize)));
    }

    /** 创建升级任务 */
    @PostMapping("/task/create")
    @Operation(summary = "创建OTA升级任务（单设备/批量）")
    public ApiResult<IotOtaUpgradeTask> createTask(@Valid @RequestBody OtaTaskDTO dto) {
        return ApiResult.ok(otaService.createUpgradeTask(
                dto.getFirmwareId(), dto.getDeviceIds(), dto.getTaskName(), dto.getOperator()));
    }

    /** 执行升级任务（向设备推送OTA通知） */
    @PostMapping("/task/{taskId}/execute")
    @Operation(summary = "执行OTA升级任务")
    public ApiResult<Void> execute(@PathVariable String taskId) {
        otaService.executeUpgradeTask(taskId);
        return ApiResult.ok(null, "升级通知已发送至设备");
    }

    /** 取消升级任务 */
    @PostMapping("/task/{taskId}/cancel")
    @Operation(summary = "取消OTA升级任务")
    public ApiResult<Void> cancel(@PathVariable String taskId,
            @RequestParam(defaultValue="system") String operator) {
        otaService.cancelUpgradeTask(taskId, operator);
        return ApiResult.ok(null);
    }
}
