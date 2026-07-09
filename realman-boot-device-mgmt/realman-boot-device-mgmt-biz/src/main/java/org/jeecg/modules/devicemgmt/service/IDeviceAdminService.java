package org.jeecg.modules.devicemgmt.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.devicemgmt.vo.AuditLogDTO;
import org.jeecg.modules.devicemgmt.vo.AuditLogQuery;
import org.jeecg.modules.devicemgmt.vo.BatchOperationResult;
import org.jeecg.modules.devicemgmt.vo.BindingCreateRequest;
import org.jeecg.modules.devicemgmt.vo.BindingDTO;
import org.jeecg.modules.devicemgmt.vo.BindingListQuery;
import org.jeecg.modules.devicemgmt.vo.DeviceLedgerDTO;
import org.jeecg.modules.devicemgmt.vo.DeviceLedgerQuery;
import org.jeecg.modules.devicemgmt.vo.OfflineRegisterItem;
import org.jeecg.modules.devicemgmt.vo.OfflineRegisterResult;
import org.jeecg.modules.devicemgmt.vo.RegistrationSecretGenerateRequest;
import org.jeecg.modules.devicemgmt.vo.RegistrationSecretGenerateResult;
import org.jeecg.modules.devicemgmt.vo.RegistrationSecretStatusResult;
import org.jeecg.modules.devicemgmt.vo.SecretResetResult;
import org.jeecg.modules.devicemgmt.vo.TenantAuthRequest;
import org.jeecg.modules.devicemgmt.vo.TokenRefreshResult;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;

import java.util.List;

/**
 * 设备管理业务平台对外 REST 的业务接口（运维人员/超管使用），对应设备基座详细设计
 * 3.4 节，区别于只服务通信中台内部调用的 {@link IDeviceMgmtService}。
 */
public interface IDeviceAdminService {

    RegistrationSecretGenerateResult generateRegistrationSecret(RegistrationSecretGenerateRequest request, String operator);

    RegistrationSecretStatusResult getRegistrationSecretStatus(String deviceCode);

    List<OfflineRegisterResult> batchOfflineRegister(List<OfflineRegisterItem> items, String operator);

    TokenRefreshResult refreshToken(String oldToken);

    void revokeToken(String deviceId, String confirmText, String reason, String operator, String operatorTenantId);

    SecretResetResult resetSecret(String deviceId, String operator);

    void changeLifecycle(String deviceId, LifecycleStage lifecycleStage, String operator);

    BindingDTO createBinding(BindingCreateRequest request, String operator);

    void deleteBinding(String bindingId, String operator);

    PageResult<BindingDTO> listBindings(BindingListQuery query);

    void grantTenantAuth(String deviceId, TenantAuthRequest request, String operator, String operatorTenantId);

    void updateTestFlag(String deviceId, Boolean testDevice, String confirmText, String operator, String operatorTenantId);

    List<BatchOperationResult> batchUpdateTestFlag(List<String> deviceCodes, boolean testDevice, String operator);

    PageResult<DeviceLedgerDTO> listLedger(DeviceLedgerQuery query);

    DeviceLedgerDTO getLedgerDetail(String deviceId);

    PageResult<AuditLogDTO> queryAuditLogs(AuditLogQuery query);
}
