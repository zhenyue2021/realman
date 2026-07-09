package org.jeecg.modules.devicemgmt.service;

import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.util.List;

/**
 * 设备管理业务平台业务接口。前四个方法一一对应
 * {@link org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient} 的四个内部方法；
 * {@code issueToken} 额外暴露给 {@link IDeviceAdminService}（Token 续签场景）复用同一套
 * JWT 签发逻辑，避免重复实现。
 */
public interface IDeviceMgmtService {

    DeviceSecretValidationResult validateSecret(String deviceCode, String deviceSecret);

    List<DeviceAclRuleDTO> getAclRules(String deviceCode);

    DeviceProvisionResult provision(DeviceProvisionRequest request);

    DeviceTokenValidationResult validateToken(String deviceToken);

    /** 签发业务身份 Device Token（JWT）。供 provision 与 Token 续签复用。 */
    String issueToken(String deviceId, DeviceType deviceType, String tenantId);
}
