package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.device.datacollect.entity.DarwinHttpOutbox;
import org.jeecg.modules.device.datacollect.mapper.DarwinHttpOutboxMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@Tag(name = "Darwin HTTP Outbox 治理", description = "查询/手动重放/死信 Darwin HTTP 出站补偿任务")
public class DarwinOutboxController {

    private final DarwinHttpOutboxMapper outboxMapper;

    @GetMapping("/internal/iot/darwin-outbox")
    @Operation(summary = "查询 Darwin HTTP Outbox")
    public Result<IPage<DarwinHttpOutbox>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String deviceCode,
                                                     @RequestParam(defaultValue = "1") long pageNo,
                                                     @RequestParam(defaultValue = "20") long pageSize) {
        Page<DarwinHttpOutbox> page = outboxMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<DarwinHttpOutbox>lambdaQuery()
                        .eq(StringUtils.hasText(status), DarwinHttpOutbox::getStatus, status)
                        .eq(StringUtils.hasText(deviceCode), DarwinHttpOutbox::getDeviceCode, deviceCode)
                        .orderByDesc(DarwinHttpOutbox::getCreatedAt));
        return Result.ok(page);
    }

    @PostMapping("/internal/iot/darwin-outbox/{id}/retry")
    @RequiresPermissions("iot:darwinOutbox:manage")
    @Operation(summary = "手动重放 Darwin HTTP Outbox")
    public Result<Void> retry(@PathVariable String id) {
        DarwinHttpOutbox update = new DarwinHttpOutbox();
        update.setId(id);
        update.setStatus("PENDING");
        update.setNextRetryAt(LocalDateTime.now());
        update.setLockedBy("");
        outboxMapper.updateById(update);
        return Result.ok();
    }

    @PostMapping("/internal/iot/darwin-outbox/{id}/dead")
    @RequiresPermissions("iot:darwinOutbox:manage")
    @Operation(summary = "手动标记 Darwin HTTP Outbox 为 DEAD")
    public Result<Void> dead(@PathVariable String id) {
        DarwinHttpOutbox update = new DarwinHttpOutbox();
        update.setId(id);
        update.setStatus("DEAD");
        outboxMapper.updateById(update);
        return Result.ok();
    }
}
