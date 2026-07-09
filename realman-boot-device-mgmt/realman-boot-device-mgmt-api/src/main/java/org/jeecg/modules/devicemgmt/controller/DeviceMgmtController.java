package org.jeecg.modules.devicemgmt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenRefreshRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenRefreshResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;
import org.jeecg.modules.devicemgmt.service.IDeviceAdminService;
import org.jeecg.modules.devicemgmt.service.IDeviceMgmtService;
import org.jeecg.modules.devicemgmt.vo.TokenRefreshResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备管理业务平台内部接口。路径与
 * {@link org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient} 逐一对应，
 * 只服务设备通信中台，不经过对外 Gateway。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "设备管理业务平台（内部）", description = "连接层密钥校验/ACL/自注册转发/业务身份Token校验")
public class DeviceMgmtController {

    private final IDeviceMgmtService deviceMgmtService;
    private final IDeviceAdminService deviceAdminService;

    @PostMapping("/internal/device/validate-secret")
    @Operation(summary = "MQTT 连接层密钥校验")
    public Result<DeviceSecretValidationResult> validateSecret(@RequestBody @Valid DeviceSecretValidationRequest request) {
        return Result.ok(deviceMgmtService.validateSecret(request.getDeviceCode(), request.getDeviceSecret()));
    }

    @GetMapping("/internal/device/{deviceCode}/acl-rules")
    @Operation(summary = "MQTT ACL 规则查询")
    public Result<List<DeviceAclRuleDTO>> getAclRules(@PathVariable String deviceCode) {
        return Result.ok(deviceMgmtService.getAclRules(deviceCode));
    }

    @PostMapping("/internal/device/provision")
    @Operation(summary = "设备上电自注册转发")
    public Result<DeviceProvisionResult> provision(@RequestBody @Valid DeviceProvisionRequest request) {
        return Result.ok(deviceMgmtService.provision(request));
    }

    @PostMapping("/internal/device/validate-token")
    @Operation(summary = "业务身份 Token 校验")
    public Result<DeviceTokenValidationResult> validateToken(@RequestBody @Valid DeviceTokenValidationRequest request) {
        return Result.ok(deviceMgmtService.validateToken(request.getDeviceToken()));
    }

    @PostMapping("/internal/device/refresh-token")
    @Operation(summary = "Device Token 续签（供设备通信中台 ota/token-refresh 上行触发）")
    public Result<DeviceTokenRefreshResult> refreshToken(@RequestBody @Valid DeviceTokenRefreshRequest request) {
        TokenRefreshResult refreshed = deviceAdminService.refreshToken(request.getOldToken());
        DeviceTokenRefreshResult dto = new DeviceTokenRefreshResult();
        dto.setDeviceToken(refreshed.getDeviceToken());
        dto.setTokenExpiresAt(refreshed.getTokenExpiresAt());
        return Result.ok(dto);
    }
}
