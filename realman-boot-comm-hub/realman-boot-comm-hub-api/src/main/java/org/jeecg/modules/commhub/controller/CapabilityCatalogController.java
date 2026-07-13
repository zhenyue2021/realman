package org.jeecg.modules.commhub.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.entity.PlatformCapability;
import org.jeecg.modules.commhub.entity.PlatformCapabilityConsumer;
import org.jeecg.modules.commhub.mapper.PlatformCapabilityConsumerMapper;
import org.jeecg.modules.commhub.mapper.PlatformCapabilityMapper;
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
@Tag(name = "平台能力目录", description = "能力元数据与消费方台账")
public class CapabilityCatalogController {

    private final PlatformCapabilityMapper capabilityMapper;
    private final PlatformCapabilityConsumerMapper consumerMapper;

    @GetMapping("/internal/capabilities")
    @Operation(summary = "查询平台能力目录")
    public Result<List<PlatformCapability>> list(@RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String status) {
        return Result.ok(capabilityMapper.selectList(Wrappers.<PlatformCapability>lambdaQuery()
                .eq(StringUtils.hasText(type), PlatformCapability::getCapabilityType, type)
                .eq(StringUtils.hasText(status), PlatformCapability::getStatus, status)
                .orderByAsc(PlatformCapability::getCapabilityCode)));
    }

    @PostMapping("/internal/capabilities")
    @RequiresPermissions("platform:capability:manage")
    @Operation(summary = "新增/更新平台能力")
    public Result<Void> upsertCapability(@RequestBody PlatformCapability capability) {
        if (!StringUtils.hasText(capability.getStatus())) {
            capability.setStatus("ACTIVE");
        }
        if (capabilityMapper.selectById(capability.getCapabilityCode()) == null) {
            capabilityMapper.insert(capability);
        } else {
            capabilityMapper.updateById(capability);
        }
        return Result.ok();
    }

    @GetMapping("/internal/capabilities/{capabilityCode}/consumers")
    @Operation(summary = "查询能力消费方")
    public Result<List<PlatformCapabilityConsumer>> consumers(@PathVariable String capabilityCode) {
        return Result.ok(consumerMapper.selectList(Wrappers.<PlatformCapabilityConsumer>lambdaQuery()
                .eq(PlatformCapabilityConsumer::getCapabilityCode, capabilityCode)
                .orderByAsc(PlatformCapabilityConsumer::getConsumerType)
                .orderByAsc(PlatformCapabilityConsumer::getConsumerId)));
    }

    @PostMapping("/internal/capabilities/{capabilityCode}/consumers")
    @RequiresPermissions("platform:capability:manage")
    @Operation(summary = "新增/更新能力消费方")
    public Result<Void> upsertConsumer(@PathVariable String capabilityCode,
                                       @RequestBody PlatformCapabilityConsumer consumer) {
        consumer.setCapabilityCode(capabilityCode);
        if (!StringUtils.hasText(consumer.getStatus())) {
            consumer.setStatus("ACTIVE");
        }
        if (StringUtils.hasText(consumer.getId()) && consumerMapper.selectById(consumer.getId()) != null) {
            consumerMapper.updateById(consumer);
        } else {
            consumerMapper.insert(consumer);
        }
        return Result.ok();
    }
}
