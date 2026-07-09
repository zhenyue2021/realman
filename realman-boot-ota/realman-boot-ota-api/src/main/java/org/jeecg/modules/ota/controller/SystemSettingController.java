package org.jeecg.modules.ota.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.util.RequestUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 系统设置读写 + 交叉校验，对齐 OTA 平台详细设计十章（PRD 9.9，17 项设置）。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "系统设置", description = "17 项设置读取/校验/落库")
public class SystemSettingController {

    private final IOtaSystemSettingService systemSettingService;

    @GetMapping("/api/v1/ota/system-settings")
    @Operation(summary = "查询全部系统设置（PRD 9.9）")
    public Result<Map<String, String>> getAll() {
        return Result.ok(systemSettingService.getAll());
    }

    @PostMapping("/api/v1/ota/system-settings/validate")
    @Operation(summary = "仅校验不落库，供前端预检（PRD 9.9 交叉校验规则）")
    public Result<Void> validate(@RequestBody Map<String, String> changes) {
        systemSettingService.validate(changes);
        return Result.ok();
    }

    @PutMapping("/api/v1/ota/system-settings")
    @RequiresPermissions("ota:system-setting:update")
    @Operation(summary = "校验通过后落库并写审计（PRD 9.9）")
    public Result<Void> update(@RequestBody Map<String, String> changes, HttpServletRequest httpRequest) {
        systemSettingService.validateAndApply(changes, RequestUtil.safeUsername(httpRequest));
        return Result.ok();
    }
}
