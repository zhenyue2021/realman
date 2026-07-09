package org.jeecg.modules.devicemgmt.contract.api.fallback;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;

import java.util.List;

/**
 * 设备管理业务平台不可用时的降级实现。这条链路直接关系到设备能否连上 MQTT，
 * 因此降级策略保守：一律拒绝（{@code allow=false} / {@code valid=false}），
 * 不因下游异常而放行未经校验的设备连接。
 */
@Slf4j
public class DeviceMgmtFeignFallback implements DeviceMgmtFeignClient {

    @Setter
    private Throwable cause;

    @Override
    public Result<DeviceSecretValidationResult> validateSecret(DeviceSecretValidationRequest request) {
        log.error("[device-mgmt] validateSecret 调用失败，服务不可用，按拒绝处理 deviceCode={}",
                request.getDeviceCode(), cause);
        DeviceSecretValidationResult result = new DeviceSecretValidationResult();
        result.setAllow(false);
        result.setReason("ERR_DEVICE_MGMT_UNAVAILABLE");
        return Result.ok(result);
    }

    @Override
    public Result<List<DeviceAclRuleDTO>> getAclRules(String deviceCode) {
        log.error("[device-mgmt] getAclRules 调用失败，服务不可用 deviceCode={}", deviceCode, cause);
        return Result.error("设备管理业务平台暂不可用：getAclRules");
    }

    @Override
    public Result<DeviceProvisionResult> provision(DeviceProvisionRequest request) {
        log.error("[device-mgmt] provision 调用失败，服务不可用 deviceCode={}", request.getDeviceCode(), cause);
        return Result.error("设备管理业务平台暂不可用，请稍后重试注册");
    }

    @Override
    public Result<DeviceTokenValidationResult> validateToken(DeviceTokenValidationRequest request) {
        log.error("[device-mgmt] validateToken 调用失败，服务不可用，按无效处理", cause);
        DeviceTokenValidationResult result = new DeviceTokenValidationResult();
        result.setValid(false);
        result.setReason("ERR_DEVICE_MGMT_UNAVAILABLE");
        return Result.ok(result);
    }
}
