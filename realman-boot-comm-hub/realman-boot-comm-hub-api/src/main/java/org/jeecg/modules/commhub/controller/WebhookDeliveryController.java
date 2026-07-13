package org.jeecg.modules.commhub.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.entity.WebhookDeliveryTask;
import org.jeecg.modules.commhub.mapper.WebhookDeliveryTaskMapper;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@Tag(name = "Webhook 投递任务治理", description = "查询/手动重试/死信 Webhook 持久化投递任务")
public class WebhookDeliveryController {

    private final WebhookDeliveryTaskMapper taskMapper;

    @GetMapping("/internal/comm-hub/webhook-deliveries")
    @Operation(summary = "查询 Webhook 投递任务")
    public Result<PageResult<WebhookDeliveryTask>> list(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String tenantId,
                                                        @RequestParam(defaultValue = "1") long pageNo,
                                                        @RequestParam(defaultValue = "20") long pageSize) {
        Page<WebhookDeliveryTask> page = taskMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<WebhookDeliveryTask>lambdaQuery()
                        .eq(StringUtils.hasText(status), WebhookDeliveryTask::getStatus, status)
                        .eq(StringUtils.hasText(tenantId), WebhookDeliveryTask::getTenantId, tenantId)
                        .orderByDesc(WebhookDeliveryTask::getCreatedAt));
        return Result.ok(new PageResult<>(page.getRecords(), page.getTotal(), pageNo, pageSize));
    }

    @PostMapping("/internal/comm-hub/webhook-deliveries/{id}/retry")
    @RequiresPermissions("commHub:webhookDelivery:manage")
    @Operation(summary = "手动重试 Webhook 投递任务")
    public Result<Void> retry(@PathVariable String id) {
        WebhookDeliveryTask update = new WebhookDeliveryTask();
        update.setId(id);
        update.setStatus("PENDING");
        update.setNextRetryAt(LocalDateTime.now());
        update.setLockedBy("");
        taskMapper.updateById(update);
        return Result.ok();
    }

    @PostMapping("/internal/comm-hub/webhook-deliveries/{id}/dead")
    @RequiresPermissions("commHub:webhookDelivery:manage")
    @Operation(summary = "手动标记 Webhook 投递任务为 DEAD")
    public Result<Void> dead(@PathVariable String id) {
        WebhookDeliveryTask update = new WebhookDeliveryTask();
        update.setId(id);
        update.setStatus("DEAD");
        taskMapper.updateById(update);
        return Result.ok();
    }
}
