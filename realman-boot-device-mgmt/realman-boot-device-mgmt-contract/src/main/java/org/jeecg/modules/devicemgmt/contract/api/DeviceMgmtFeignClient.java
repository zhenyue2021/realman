package org.jeecg.modules.devicemgmt.contract.api;

import jakarta.validation.Valid;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.ServiceNameConstants;
import org.jeecg.modules.devicemgmt.contract.api.fallback.DeviceMgmtFeignFallbackFactory;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenRefreshRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenRefreshResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 设备管理业务平台（{@code realman-device-mgmt}）对内 Feign 契约。
 *
 * <p>只覆盖设备通信中台需要同步调用的内部能力，见本模块 pom.xml 说明与设备通信中台
 * 详细设计 2.3（连接层鉴权）、3.1（自注册转发）。台账/审计/凭证生成等面向运维人员
 * 的对外 REST 走 WEB 端向网关反向代理，属于 Phase 2 的 api 子模块范畴，不在本契约里。
 */
@FeignClient(
        contextId = "deviceMgmtFeignClient",
        value = ServiceNameConstants.SERVICE_DEVICE_MGMT,
        path = "${realman.device-mgmt.context-path:/realman-device-mgmt}",
        fallbackFactory = DeviceMgmtFeignFallbackFactory.class
)
public interface DeviceMgmtFeignClient {

    /** MQTT 连接层密钥校验。调用方：设备通信中台（EMQX Auth 回调）。 */
    @PostMapping("/internal/device/validate-secret")
    Result<DeviceSecretValidationResult> validateSecret(@RequestBody @Valid DeviceSecretValidationRequest request);

    /** MQTT ACL 规则查询。调用方：设备通信中台（EMQX ACL 回调）。 */
    @GetMapping("/internal/device/{deviceCode}/acl-rules")
    Result<List<DeviceAclRuleDTO>> getAclRules(@PathVariable("deviceCode") String deviceCode);

    /** 设备上电自注册转发。调用方：设备通信中台（南向唯一 HTTP 例外的转发目标）。 */
    @PostMapping("/internal/device/provision")
    Result<DeviceProvisionResult> provision(@RequestBody @Valid DeviceProvisionRequest request);

    /** 业务身份 Token 校验。调用方：设备通信中台（归一化 MQTT/HTTP 业务报文时）。 */
    @PostMapping("/internal/device/validate-token")
    Result<DeviceTokenValidationResult> validateToken(@RequestBody @Valid DeviceTokenValidationRequest request);

    /**
     * Device Token 续签。调用方：设备通信中台，上行 {@code ota/token-refresh} 时触发，
     * 续签结果由通信中台下行回传给设备（见 OTA 平台详细设计第二章协议映射表）。
     */
    @PostMapping("/internal/device/refresh-token")
    Result<DeviceTokenRefreshResult> refreshToken(@RequestBody @Valid DeviceTokenRefreshRequest request);
}
