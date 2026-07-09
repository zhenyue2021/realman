package org.jeecg.modules.devicemgmt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.devicemgmt.service.IDeviceAdminService;
import org.jeecg.modules.devicemgmt.util.RequestUtil;
import org.jeecg.modules.devicemgmt.vo.AuditLogDTO;
import org.jeecg.modules.devicemgmt.vo.AuditLogQuery;
import org.jeecg.modules.devicemgmt.vo.BatchOperationResult;
import org.jeecg.modules.devicemgmt.vo.BindingCreateRequest;
import org.jeecg.modules.devicemgmt.vo.BindingDTO;
import org.jeecg.modules.devicemgmt.vo.BindingListQuery;
import org.jeecg.modules.devicemgmt.vo.DeviceLedgerDTO;
import org.jeecg.modules.devicemgmt.vo.DeviceLedgerQuery;
import org.jeecg.modules.devicemgmt.vo.LifecycleChangeRequest;
import org.jeecg.modules.devicemgmt.vo.OfflineRegisterItem;
import org.jeecg.modules.devicemgmt.vo.OfflineRegisterResult;
import org.jeecg.modules.devicemgmt.vo.RegistrationSecretGenerateRequest;
import org.jeecg.modules.devicemgmt.vo.RegistrationSecretGenerateResult;
import org.jeecg.modules.devicemgmt.vo.RegistrationSecretStatusResult;
import org.jeecg.modules.devicemgmt.vo.SecretResetResult;
import org.jeecg.modules.devicemgmt.vo.TenantAuthRequest;
import org.jeecg.modules.devicemgmt.vo.TestFlagBatchRequest;
import org.jeecg.modules.devicemgmt.vo.TestFlagRequest;
import org.jeecg.modules.devicemgmt.vo.TokenRefreshResult;
import org.jeecg.modules.devicemgmt.vo.TokenRevokeRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备管理业务平台对外 REST（运维/超管使用）。物理上经设备通信中台的 WEB 端向网关
 * 统一入口/鉴权/限流后反向代理，见设备通信中台详细设计 4.2 节；本控制器只负责
 * 业务逻辑本身，不重复做网关层已完成的路由/限流。
 *
 * <p>操作人身份沿用 {@code realman-boot-iot} 既有约定：{@link RequestUtil#safeUsername}
 * 从 {@code X-Access-Token} 解析用户名；跨租户操作另携带 {@code X-Operator-Tenant-Id}。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "设备管理业务平台（对外）", description = "注册凭证/Token与密钥生命周期/绑定/租户授权/测试标记/台账/审计")
public class DeviceAdminController {

    private final IDeviceAdminService deviceAdminService;

    @PostMapping("/api/v1/admin/devices/registration-secret")
    @RequiresPermissions("deviceMgmt:registrationSecret:generate")
    @Operation(summary = "生成一次性注册凭证")
    public Result<RegistrationSecretGenerateResult> generateRegistrationSecret(
            @RequestBody @Valid RegistrationSecretGenerateRequest request, HttpServletRequest httpRequest) {
        return Result.ok(deviceAdminService.generateRegistrationSecret(request, RequestUtil.safeUsername(httpRequest)));
    }

    @GetMapping("/api/v1/admin/devices/{deviceSn}/registration-secret/status")
    @Operation(summary = "查询一次性注册凭证状态")
    public Result<RegistrationSecretStatusResult> getRegistrationSecretStatus(@PathVariable String deviceSn) {
        return Result.ok(deviceAdminService.getRegistrationSecretStatus(deviceSn));
    }

    @PostMapping("/api/v1/admin/devices/offline-register/batch")
    @RequiresPermissions("deviceMgmt:offlineRegister:batch")
    @Operation(summary = "训练场批量离线注册")
    public Result<List<OfflineRegisterResult>> batchOfflineRegister(
            @RequestBody @Valid List<OfflineRegisterItem> items, HttpServletRequest httpRequest) {
        return Result.ok(deviceAdminService.batchOfflineRegister(items, RequestUtil.safeUsername(httpRequest)));
    }

    @PostMapping("/api/v1/devices/token/refresh")
    @Operation(summary = "Token 续签（到期前 30 天内，设备直接调用）")
    public Result<TokenRefreshResult> refreshToken(@RequestHeader("Authorization") String authorization) {
        return Result.ok(deviceAdminService.refreshToken(bearerToken(authorization)));
    }

    @PutMapping("/api/v1/devices/{deviceId}/token/revoke")
    @RequiresPermissions("deviceMgmt:token:revoke")
    @Operation(summary = "吊销 Token（需 confirmText=REVOKE_TOKEN 二次确认）")
    public Result<Void> revokeToken(@PathVariable String deviceId, @RequestBody @Valid TokenRevokeRequest request,
                                     HttpServletRequest httpRequest) {
        deviceAdminService.revokeToken(deviceId, request.getConfirmText(), request.getReason(),
                RequestUtil.safeUsername(httpRequest), RequestUtil.operatorTenantId(httpRequest));
        return Result.ok();
    }

    @PostMapping("/api/v1/devices/{deviceId}/secret/reset")
    @RequiresPermissions("deviceMgmt:secret:reset")
    @Operation(summary = "重置 MQTT 连接层密钥")
    public Result<SecretResetResult> resetSecret(@PathVariable String deviceId, HttpServletRequest httpRequest) {
        return Result.ok(deviceAdminService.resetSecret(deviceId, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/devices/{deviceId}/lifecycle")
    @RequiresPermissions("deviceMgmt:lifecycle:change")
    @Operation(summary = "变更设备生命周期阶段")
    public Result<Void> changeLifecycle(@PathVariable String deviceId, @RequestBody @Valid LifecycleChangeRequest request,
                                         HttpServletRequest httpRequest) {
        deviceAdminService.changeLifecycle(deviceId, request.getLifecycleStage(), RequestUtil.safeUsername(httpRequest));
        return Result.ok();
    }

    @PostMapping("/api/v1/devices/bindings")
    @RequiresPermissions("deviceMgmt:binding:add")
    @Operation(summary = "创建主控端-机器人绑定")
    public Result<BindingDTO> createBinding(@RequestBody @Valid BindingCreateRequest request, HttpServletRequest httpRequest) {
        return Result.ok(deviceAdminService.createBinding(request, RequestUtil.safeUsername(httpRequest)));
    }

    @DeleteMapping("/api/v1/devices/bindings/{bindingId}")
    @RequiresPermissions("deviceMgmt:binding:delete")
    @Operation(summary = "解除绑定")
    public Result<Void> deleteBinding(@PathVariable String bindingId, HttpServletRequest httpRequest) {
        deviceAdminService.deleteBinding(bindingId, RequestUtil.safeUsername(httpRequest));
        return Result.ok();
    }

    @GetMapping("/api/v1/devices/bindings")
    @Operation(summary = "查询绑定关系")
    public Result<PageResult<BindingDTO>> listBindings(BindingListQuery query) {
        return Result.ok(deviceAdminService.listBindings(query));
    }

    @PostMapping("/api/v1/devices/{deviceId}/tenant-auth")
    @RequiresPermissions("deviceMgmt:tenantAuth:grant")
    @Operation(summary = "设备-租户授权")
    public Result<Void> grantTenantAuth(@PathVariable String deviceId, @RequestBody @Valid TenantAuthRequest request,
                                         HttpServletRequest httpRequest) {
        deviceAdminService.grantTenantAuth(deviceId, request, RequestUtil.safeUsername(httpRequest),
                RequestUtil.operatorTenantId(httpRequest));
        return Result.ok();
    }

    @PutMapping("/api/v1/devices/{deviceId}/test-flag")
    @RequiresPermissions("deviceMgmt:testFlag:update")
    @Operation(summary = "标记/取消标记测试设备（取消标记需 confirmText=UNSET_TEST_FLAG）")
    public Result<Void> updateTestFlag(@PathVariable String deviceId, @RequestBody @Valid TestFlagRequest request,
                                        HttpServletRequest httpRequest) {
        deviceAdminService.updateTestFlag(deviceId, request.getTestDevice(), request.getConfirmText(),
                RequestUtil.safeUsername(httpRequest), RequestUtil.operatorTenantId(httpRequest));
        return Result.ok();
    }

    @PostMapping("/api/v1/devices/test-flag/batch")
    @RequiresPermissions("deviceMgmt:testFlag:batch")
    @Operation(summary = "批量标记测试设备（仅支持标记，取消标记须逐台操作）")
    public Result<List<BatchOperationResult>> batchTestFlag(@RequestBody @Valid TestFlagBatchRequest request,
                                                              HttpServletRequest httpRequest) {
        if (!Boolean.TRUE.equals(request.getTestDevice())) {
            throw new JeecgBootBizTipException("ERR_BATCH_UNSET_NOT_SUPPORTED");
        }
        return Result.ok(deviceAdminService.batchUpdateTestFlag(request.getDeviceCodes(), true,
                RequestUtil.safeUsername(httpRequest)));
    }

    @GetMapping("/api/v1/devices")
    @Operation(summary = "设备台账列表（聚合 SSOT 基础信息 + 本层凭证/绑定状态）")
    public Result<PageResult<DeviceLedgerDTO>> listLedger(DeviceLedgerQuery query) {
        return Result.ok(deviceAdminService.listLedger(query));
    }

    @GetMapping("/api/v1/devices/{deviceId}")
    @Operation(summary = "设备台账详情")
    public Result<DeviceLedgerDTO> getLedgerDetail(@PathVariable String deviceId) {
        return Result.ok(deviceAdminService.getLedgerDetail(deviceId));
    }

    @GetMapping("/api/v1/devices/audit-logs")
    @Operation(summary = "操作审计日志查询")
    public Result<PageResult<AuditLogDTO>> queryAuditLogs(AuditLogQuery query) {
        return Result.ok(deviceAdminService.queryAuditLogs(query));
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorizationHeader.substring(7).trim();
        }
        return authorizationHeader;
    }
}
