package org.jeecg.modules.ota.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.service.IOtaTaskService;
import org.jeecg.modules.ota.util.RequestUtil;
import org.jeecg.modules.ota.vo.TaskCreateRequest;
import org.jeecg.modules.ota.vo.TaskDTO;
import org.jeecg.modules.ota.vo.TaskListQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 升级任务管理，对齐 OTA 平台详细设计五章/八章（PRD 4.4、9.5）。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "升级任务管理", description = "创建/查询/重试/取消/回滚/继续/终止")
public class TaskController {

    private final IOtaTaskService taskService;

    @PostMapping("/api/v1/ota/tasks")
    @RequiresPermissions("ota:task:create")
    @Operation(summary = "创建升级任务（PRD 9.5.1）")
    public Result<TaskDTO> create(@Valid @RequestBody TaskCreateRequest request, HttpServletRequest httpRequest) {
        return Result.ok(taskService.create(request, RequestUtil.safeUsername(httpRequest),
                RequestUtil.operatorTenantId(httpRequest)));
    }

    @GetMapping("/api/v1/ota/tasks")
    @Operation(summary = "查询任务列表（PRD 9.5.2）")
    public Result<PageResult<TaskDTO>> list(TaskListQuery query) {
        return Result.ok(taskService.list(query));
    }

    @GetMapping("/api/v1/ota/tasks/{taskId}")
    @Operation(summary = "查询任务详情（PRD 9.5.3）")
    public Result<TaskDTO> detail(@PathVariable String taskId) {
        return Result.ok(taskService.detail(taskId));
    }

    @PutMapping("/api/v1/ota/tasks/{taskId}/retry")
    @RequiresPermissions("ota:task:retry")
    @Operation(summary = "单设备任务重试（PRD 9.5.4，前置态 FAILED/ROLLBACK_FAILED）")
    public Result<TaskDTO> retry(@PathVariable String taskId, HttpServletRequest httpRequest) {
        return Result.ok(taskService.retry(taskId, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/ota/tasks/{taskId}/cancel")
    @RequiresPermissions("ota:task:cancel")
    @Operation(summary = "单设备任务取消（PRD 9.5.5）")
    public Result<TaskDTO> cancel(@PathVariable String taskId, HttpServletRequest httpRequest) {
        return Result.ok(taskService.cancel(taskId, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/ota/tasks/{taskId}/rollback")
    @RequiresPermissions("ota:task:rollback")
    @Operation(summary = "单设备任务回滚（PRD 9.5.6，前置态 FAILED/ROLLBACK_FAILED）")
    public Result<TaskDTO> rollback(@PathVariable String taskId, HttpServletRequest httpRequest) {
        return Result.ok(taskService.rollback(taskId, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/ota/tasks/{taskId}/retry-failed")
    @RequiresPermissions("ota:task:retry")
    @Operation(summary = "批量任务失败设备重试（PRD 9.5.7）")
    public Result<TaskDTO> retryFailed(@PathVariable String taskId, HttpServletRequest httpRequest) {
        return Result.ok(taskService.retryFailed(taskId, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/ota/tasks/{taskId}/resume")
    @RequiresPermissions("ota:task:resume")
    @Operation(summary = "继续 PAUSED 批量任务（PRD 9.5.8，请求体透传以区分字段缺失/显式 null）")
    public Result<TaskDTO> resume(@PathVariable String taskId, @RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest httpRequest) {
        return Result.ok(taskService.resume(taskId, body, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/ota/tasks/{taskId}/abort")
    @RequiresPermissions("ota:task:abort")
    @Operation(summary = "终止 PAUSED 批量任务（PRD 9.5.9）")
    public Result<TaskDTO> abort(@PathVariable String taskId, HttpServletRequest httpRequest) {
        return Result.ok(taskService.abort(taskId, RequestUtil.safeUsername(httpRequest)));
    }
}
