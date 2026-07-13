package org.jeecg.modules.commhub.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.entity.CommHubEventSchema;
import org.jeecg.modules.commhub.mapper.CommHubEventSchemaMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "上行事件 Schema", description = "DeviceUplinkEvent payload JSON Schema 台账")
public class EventSchemaController {

    private final CommHubEventSchemaMapper schemaMapper;

    @GetMapping("/api/v1/event-schemas/{eventKind}")
    @Operation(summary = "查询指定事件类型的 Schema")
    public Result<List<CommHubEventSchema>> list(@PathVariable String eventKind,
                                                 @RequestParam(required = false) String status) {
        return Result.ok(schemaMapper.selectList(Wrappers.<CommHubEventSchema>lambdaQuery()
                .eq(CommHubEventSchema::getEventKind, eventKind)
                .eq(StringUtils.hasText(status), CommHubEventSchema::getStatus, status)
                .orderByDesc(CommHubEventSchema::getSchemaVersion)));
    }

    @PostMapping("/api/v1/event-schemas")
    @RequiresPermissions("commHub:eventSchema:manage")
    @Operation(summary = "新增/更新事件 Schema")
    public Result<Void> upsert(@RequestBody CommHubEventSchema schema) {
        if (!StringUtils.hasText(schema.getStatus())) {
            schema.setStatus("ACTIVE");
        }
        if (StringUtils.hasText(schema.getId()) && schemaMapper.selectById(schema.getId()) != null) {
            schemaMapper.updateById(schema);
        } else {
            schemaMapper.insert(schema);
        }
        return Result.ok();
    }
}
