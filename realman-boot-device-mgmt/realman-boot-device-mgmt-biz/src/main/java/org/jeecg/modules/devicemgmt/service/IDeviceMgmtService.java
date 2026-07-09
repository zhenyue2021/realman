package org.jeecg.modules.devicemgmt.service;

import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;

import java.util.List;

/**
 * 设备管理业务平台业务接口，一一对应
 * {@link org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient} 的四个方法。
 */
public interface IDeviceMgmtService {

    DeviceSecretValidationResult validateSecret(String deviceCode, String deviceSecret);

    List<DeviceAclRuleDTO> getAclRules(String deviceCode);

    DeviceProvisionResult provision(DeviceProvisionRequest request);

    DeviceTokenValidationResult validateToken(String deviceToken);
}
