package org.jeecg.modules.devicemgmt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.devicemgmt.service.IDeviceMigrationService;
import org.jeecg.modules.devicemgmt.util.RequestUtil;
import org.jeecg.modules.devicemgmt.vo.LegacyDeviceMigrationRequest;
import org.jeecg.modules.devicemgmt.vo.LegacyDeviceMigrationResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存量设备一次性迁移入口（{@code iot_device} → {@code device_info}/{@code device_credential}）。
 * 只在部署时显式开启 {@code realman.migration.legacy-iot.enabled=true} 才可用（见
 * {@link org.jeecg.modules.devicemgmt.migration.LegacyIotDataSourceConfig}）；默认关闭时
 * 本接口直接返回"迁移未启用"，不暴露到日常操作面，迁移窗口结束后应重新关闭该配置。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "存量设备迁移（一次性）", description = "iot_device -> device_info/device_credential")
public class DeviceMigrationController {

    private final ObjectProvider<IDeviceMigrationService> migrationServiceProvider;

    @PostMapping("/api/v1/admin/devices/migrate-from-legacy-iot")
    @RequiresPermissions("deviceMgmt:migration:execute")
    @Operation(summary = "执行存量设备迁移（需 confirmText=MIGRATE_LEGACY_DEVICES 二次确认）")
    public Result<LegacyDeviceMigrationResult> migrate(@RequestBody @Valid LegacyDeviceMigrationRequest request,
                                                        HttpServletRequest httpRequest) {
        IDeviceMigrationService migrationService = migrationServiceProvider.getIfAvailable();
        if (migrationService == null) {
            throw new JeecgBootBizTipException("ERR_MIGRATION_NOT_ENABLED：请先在部署配置里开启 realman.migration.legacy-iot.enabled");
        }
        return Result.ok(migrationService.migrateFromLegacyIot(request.getConfirmText(), RequestUtil.safeUsername(httpRequest)));
    }
}
