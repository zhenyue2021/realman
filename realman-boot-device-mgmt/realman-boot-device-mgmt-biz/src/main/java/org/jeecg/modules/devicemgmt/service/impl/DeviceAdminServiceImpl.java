package org.jeecg.modules.devicemgmt.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.BindingUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceListQuery;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceRegisterWriteRequest;
import org.jeecg.modules.deviceinfo.contract.dto.LifecycleUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.deviceinfo.contract.dto.TestFlagUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;
import org.jeecg.modules.devicemgmt.config.DeviceRegistrationProperties;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenValidationResult;
import org.jeecg.modules.devicemgmt.entity.DeviceBinding;
import org.jeecg.modules.devicemgmt.entity.DeviceCredential;
import org.jeecg.modules.devicemgmt.entity.DeviceOperationAuditLog;
import org.jeecg.modules.devicemgmt.entity.DeviceRegistrationSecret;
import org.jeecg.modules.devicemgmt.entity.DeviceTenantAuth;
import org.jeecg.modules.devicemgmt.mapper.DeviceBindingMapper;
import org.jeecg.modules.devicemgmt.mapper.DeviceCredentialMapper;
import org.jeecg.modules.devicemgmt.mapper.DeviceOperationAuditLogMapper;
import org.jeecg.modules.devicemgmt.mapper.DeviceRegistrationSecretMapper;
import org.jeecg.modules.devicemgmt.mapper.DeviceTenantAuthMapper;
import org.jeecg.modules.devicemgmt.service.DeviceSecretCacheService;
import org.jeecg.modules.devicemgmt.service.IDeviceAdminService;
import org.jeecg.modules.devicemgmt.service.IDeviceMgmtService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备管理业务平台对外 REST 的业务实现，对应设备基座详细设计 3.4/3.5 节。
 *
 * <p>已知范围限制：测试设备取消标记的"是否存在进行中 high_risk 任务"前置校验依赖 OTA
 * 平台（本仓库尚未落地 OTA 服务/契约），本实现按文档 3.5 节时序图跳过该外部校验，
 * 只保留二次确认（{@code confirmText}）与审计留痕；OTA 契约就绪后需在
 * {@link #updateTestFlag} 中补上该调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAdminServiceImpl implements IDeviceAdminService {

    private static final String ERR_DEVICE_NOT_FOUND = "ERR_DEVICE_NOT_FOUND";
    private static final String ERR_CREDENTIAL_NOT_FOUND = "ERR_CREDENTIAL_NOT_FOUND";
    private static final String ERR_BINDING_NOT_FOUND = "ERR_BINDING_NOT_FOUND";
    private static final String ERR_ALREADY_BOUND = "ERR_ALREADY_BOUND";
    private static final String ERR_DEVICE_ALREADY_REGISTERED = "ERR_DEVICE_ALREADY_REGISTERED";
    private static final String CONFIRM_REVOKE_TOKEN = "REVOKE_TOKEN";
    private static final String CONFIRM_UNSET_TEST_FLAG = "UNSET_TEST_FLAG";

    private static final String AUDIT_NORMAL = "normal";
    private static final String AUDIT_HIGH = "high";

    private final DeviceCredentialMapper credentialMapper;
    private final DeviceRegistrationSecretMapper registrationSecretMapper;
    private final DeviceBindingMapper bindingMapper;
    private final DeviceTenantAuthMapper tenantAuthMapper;
    private final DeviceOperationAuditLogMapper auditLogMapper;
    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final IDeviceMgmtService deviceMgmtService;
    private final DeviceRegistrationProperties registrationProperties;
    private final DeviceSecretCacheService secretCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationSecretGenerateResult generateRegistrationSecret(RegistrationSecretGenerateRequest request, String operator) {
        String plainSecret = IdUtil.fastSimpleUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(registrationProperties.getSecretExpiryDays());

        DeviceRegistrationSecret secret = new DeviceRegistrationSecret();
        secret.setId(IdUtil.fastSimpleUUID());
        secret.setDeviceCode(request.getDeviceCode());
        secret.setTenantId(request.getTenantId());
        secret.setSecretHash(DigestUtil.sha256Hex(plainSecret));
        secret.setStatus("UNUSED");
        secret.setExpiresAt(expiresAt);
        secret.setCreatedBy(operator);
        registrationSecretMapper.insert(secret);

        writeAudit(null, "REGISTRATION_SECRET_GENERATE", operator, request.getTenantId(), request.getTenantId(),
                AUDIT_NORMAL, Map.of("deviceCode", request.getDeviceCode(), "secretId", secret.getId()));

        RegistrationSecretGenerateResult result = new RegistrationSecretGenerateResult();
        result.setSecretId(secret.getId());
        result.setDeviceRegistrationSecret(plainSecret);
        result.setExpiresAt(expiresAt);
        return result;
    }

    @Override
    public RegistrationSecretStatusResult getRegistrationSecretStatus(String deviceCode) {
        DeviceRegistrationSecret secret = registrationSecretMapper.selectOne(Wrappers.<DeviceRegistrationSecret>lambdaQuery()
                .eq(DeviceRegistrationSecret::getDeviceCode, deviceCode)
                .orderByDesc(DeviceRegistrationSecret::getCreatedAt)
                .last("LIMIT 1"));

        RegistrationSecretStatusResult result = new RegistrationSecretStatusResult();
        result.setDeviceCode(deviceCode);
        if (secret == null) {
            result.setStatus("NOT_FOUND");
            return result;
        }
        boolean effectivelyExpired = "UNUSED".equals(secret.getStatus())
                && secret.getExpiresAt() != null && secret.getExpiresAt().isBefore(LocalDateTime.now());
        result.setStatus(effectivelyExpired ? "EXPIRED" : secret.getStatus());
        result.setExpiresAt(secret.getExpiresAt());
        result.setUsedAt(secret.getUsedAt());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<OfflineRegisterResult> batchOfflineRegister(List<OfflineRegisterItem> items, String operator) {
        List<OfflineRegisterResult> results = new ArrayList<>(items.size());
        for (OfflineRegisterItem item : items) {
            OfflineRegisterResult result = new OfflineRegisterResult();
            result.setDeviceCode(item.getDeviceCode());
            try {
                if (getDeviceByCodeSafely(item.getDeviceCode()) != null) {
                    throw new JeecgBootBizTipException(ERR_DEVICE_ALREADY_REGISTERED);
                }
                String deviceId = IdUtil.fastSimpleUUID();
                DeviceRegisterWriteRequest registerRequest = new DeviceRegisterWriteRequest();
                registerRequest.setDeviceId(deviceId);
                registerRequest.setDeviceCode(item.getDeviceCode());
                registerRequest.setDeviceType(item.getDeviceType());
                registerRequest.setTenantId(item.getTenantId());
                registerRequest.setDeviceModel(item.getDeviceModel());
                Result<Void> registerResult = deviceInfoFeignClient.register(registerRequest);
                if (registerResult == null || !registerResult.isSuccess()) {
                    throw new JeecgBootBizTipException("写入设备信息基础服务失败：" + item.getDeviceCode());
                }
                result.setDeviceId(deviceId);
                result.setSuccess(true);
            } catch (Exception e) {
                log.warn("[device-mgmt] 离线注册失败 deviceCode={}: {}", item.getDeviceCode(), e.getMessage());
                result.setSuccess(false);
                result.setMessage(e.getMessage());
            }
            results.add(result);
        }
        int successCount = (int) results.stream().filter(OfflineRegisterResult::isSuccess).count();
        writeAudit(null, "OFFLINE_REGISTER_BATCH", operator, null, null, AUDIT_NORMAL,
                Map.of("count", items.size(), "successCount", successCount));
        return results;
    }

    @Override
    public TokenRefreshResult refreshToken(String oldToken) {
        DeviceTokenValidationResult validation = deviceMgmtService.validateToken(oldToken);
        if (!validation.isValid()) {
            throw new JeecgBootBizTipException(validation.getReason());
        }
        DeviceCredential credential = credentialMapper.selectById(validation.getDeviceId());
        if (credential == null) {
            throw new JeecgBootBizTipException(ERR_CREDENTIAL_NOT_FOUND);
        }
        String newToken = deviceMgmtService.issueToken(validation.getDeviceId(), validation.getDeviceType(), validation.getTenantId());
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(365);
        credential.setTokenJti(IdUtil.fastSimpleUUID());
        credential.setTokenIssuedAt(LocalDateTime.now());
        credential.setTokenExpiresAt(newExpiresAt);
        credentialMapper.updateById(credential);

        TokenRefreshResult result = new TokenRefreshResult();
        result.setDeviceToken(newToken);
        result.setTokenExpiresAt(newExpiresAt);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeToken(String deviceId, String confirmText, String reason, String operator, String operatorTenantId) {
        if (!CONFIRM_REVOKE_TOKEN.equals(confirmText)) {
            throw new JeecgBootBizTipException("ERR_CONFIRM_TEXT_MISMATCH");
        }
        DeviceCredential credential = credentialMapper.selectById(deviceId);
        if (credential == null) {
            throw new JeecgBootBizTipException(ERR_CREDENTIAL_NOT_FOUND);
        }
        credential.setTokenRevokedAt(LocalDateTime.now());
        credential.setTokenRevokeReason(reason);
        credentialMapper.updateById(credential);

        writeAudit(deviceId, "TOKEN_REVOKE", operator, operatorTenantId, targetTenantIdOf(deviceId), AUDIT_HIGH,
                Map.of("reason", reason == null ? "" : reason));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecretResetResult resetSecret(String deviceId, String operator) {
        DeviceCredential credential = credentialMapper.selectById(deviceId);
        if (credential == null) {
            throw new JeecgBootBizTipException(ERR_CREDENTIAL_NOT_FOUND);
        }
        String plainSecret = IdUtil.fastSimpleUUID();
        credential.setDeviceSecretHash(DigestUtil.sha256Hex(plainSecret));
        credential.setDeviceSecretVersion(
                credential.getDeviceSecretVersion() == null ? 1 : credential.getDeviceSecretVersion() + 1);
        credentialMapper.updateById(credential);

        DeviceInfoDTO device = getDeviceSafely(deviceId);
        if (device != null) {
            secretCacheService.evict(device.getDeviceCode());
        }

        writeAudit(deviceId, "SECRET_RESET", operator, null, targetTenantIdOf(deviceId), AUDIT_HIGH, Map.of(
                "deviceSecretVersion", credential.getDeviceSecretVersion()));

        SecretResetResult result = new SecretResetResult();
        result.setDeviceSecret(plainSecret);
        result.setDeviceSecretVersion(credential.getDeviceSecretVersion());
        return result;
    }

    @Override
    public void changeLifecycle(String deviceId, LifecycleStage lifecycleStage, String operator) {
        LifecycleUpdateRequest request = new LifecycleUpdateRequest();
        request.setLifecycleStage(lifecycleStage);
        Result<Void> result = deviceInfoFeignClient.updateLifecycle(deviceId, request);
        if (result == null || !result.isSuccess()) {
            throw new JeecgBootBizTipException("生命周期同步 SSOT 失败：" + deviceId);
        }
        writeAudit(deviceId, "LIFECYCLE_CHANGE", operator, null, targetTenantIdOf(deviceId), AUDIT_NORMAL,
                Map.of("lifecycleStage", lifecycleStage.name()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BindingDTO createBinding(BindingCreateRequest request, String operator) {
        boolean oneToOne = !"V2_MANY_TO_MANY".equals(request.getBindMode());
        if (oneToOne) {
            long masterActive = bindingMapper.selectCount(Wrappers.<DeviceBinding>lambdaQuery()
                    .eq(DeviceBinding::getMasterDeviceId, request.getMasterDeviceId())
                    .eq(DeviceBinding::getStatus, "ACTIVE"));
            long slaveActive = bindingMapper.selectCount(Wrappers.<DeviceBinding>lambdaQuery()
                    .eq(DeviceBinding::getSlaveDeviceId, request.getSlaveDeviceId())
                    .eq(DeviceBinding::getStatus, "ACTIVE"));
            if (masterActive > 0 || slaveActive > 0) {
                throw new JeecgBootBizTipException(ERR_ALREADY_BOUND);
            }
        }

        DeviceBinding binding = new DeviceBinding();
        binding.setId(IdUtil.fastSimpleUUID());
        binding.setMasterDeviceId(request.getMasterDeviceId());
        binding.setSlaveDeviceId(request.getSlaveDeviceId());
        binding.setTenantId(request.getTenantId());
        binding.setBindMode(request.getBindMode());
        binding.setStatus("ACTIVE");
        binding.setCreatedBy(operator);
        bindingMapper.insert(binding);

        // V1 一对一快照同步；V2 多对多的追加式快照留待该能力排期后实现（见设备基座详细设计六 开放问题）
        syncBindingSnapshot(request.getMasterDeviceId(), List.of(request.getSlaveDeviceId()));
        syncBindingSnapshot(request.getSlaveDeviceId(), List.of(request.getMasterDeviceId()));

        writeAudit(request.getMasterDeviceId(), "BINDING_CREATE", operator, null, request.getTenantId(), AUDIT_NORMAL,
                Map.of("bindingId", binding.getId(), "slaveDeviceId", request.getSlaveDeviceId()));

        return toBindingDTO(binding);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBinding(String bindingId, String operator) {
        DeviceBinding binding = bindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new JeecgBootBizTipException(ERR_BINDING_NOT_FOUND);
        }
        binding.setStatus("REVOKED");
        bindingMapper.updateById(binding);

        syncBindingSnapshot(binding.getMasterDeviceId(), Collections.emptyList());
        syncBindingSnapshot(binding.getSlaveDeviceId(), Collections.emptyList());

        writeAudit(binding.getMasterDeviceId(), "BINDING_DELETE", operator, null, binding.getTenantId(), AUDIT_NORMAL,
                Map.of("bindingId", bindingId));
    }

    @Override
    public PageResult<BindingDTO> listBindings(BindingListQuery query) {
        Page<DeviceBinding> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<DeviceBinding> pageResult = bindingMapper.selectPage(page, Wrappers.<DeviceBinding>lambdaQuery()
                .eq(StringUtils.hasText(query.getMasterDeviceId()), DeviceBinding::getMasterDeviceId, query.getMasterDeviceId())
                .eq(StringUtils.hasText(query.getSlaveDeviceId()), DeviceBinding::getSlaveDeviceId, query.getSlaveDeviceId())
                .eq(StringUtils.hasText(query.getTenantId()), DeviceBinding::getTenantId, query.getTenantId())
                .eq(StringUtils.hasText(query.getStatus()), DeviceBinding::getStatus, query.getStatus())
                .orderByDesc(DeviceBinding::getCreatedAt));

        List<BindingDTO> records = pageResult.getRecords().stream().map(this::toBindingDTO).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantTenantAuth(String deviceId, TenantAuthRequest request, String operator, String operatorTenantId) {
        DeviceTenantAuth auth = new DeviceTenantAuth();
        auth.setId(IdUtil.fastSimpleUUID());
        auth.setDeviceId(deviceId);
        auth.setTenantId(request.getTenantId());
        auth.setGrantedBy(operator);
        auth.setValidUntil(request.getValidUntil());
        tenantAuthMapper.insert(auth);

        boolean crossTenant = StringUtils.hasText(operatorTenantId) && !operatorTenantId.equals(request.getTenantId());
        writeAudit(deviceId, "TENANT_AUTH", operator, operatorTenantId, request.getTenantId(),
                crossTenant ? AUDIT_HIGH : AUDIT_NORMAL, Map.of("grantedTenantId", request.getTenantId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTestFlag(String deviceId, Boolean testDevice, String confirmText, String operator, String operatorTenantId) {
        DeviceInfoDTO device = getDeviceSafely(deviceId);
        if (device == null) {
            throw new JeecgBootBizTipException(ERR_DEVICE_NOT_FOUND);
        }
        String auditLevel = AUDIT_NORMAL;
        if (Boolean.FALSE.equals(testDevice)) {
            if (!CONFIRM_UNSET_TEST_FLAG.equals(confirmText)) {
                throw new JeecgBootBizTipException("ERR_CONFIRM_TEXT_MISMATCH");
            }
            // 已知限制：OTA 契约未落地，跳过"是否存在进行中 high_risk 任务"前置校验，见类注释
            auditLevel = AUDIT_HIGH;
        }
        TestFlagUpdateRequest request = new TestFlagUpdateRequest();
        request.setTestDevice(testDevice);
        Result<Void> result = deviceInfoFeignClient.updateTestFlag(deviceId, request);
        if (result == null || !result.isSuccess()) {
            throw new JeecgBootBizTipException("测试标记同步 SSOT 失败：" + deviceId);
        }
        writeAudit(deviceId, "TEST_FLAG", operator, operatorTenantId, device.getTenantId(), auditLevel,
                Map.of("testDevice", testDevice));
    }

    @Override
    public List<BatchOperationResult> batchUpdateTestFlag(List<String> deviceCodes, boolean testDevice, String operator) {
        if (!testDevice) {
            throw new JeecgBootBizTipException("ERR_BATCH_UNSET_NOT_SUPPORTED");
        }
        List<BatchOperationResult> results = new ArrayList<>(deviceCodes.size());
        for (String deviceCode : deviceCodes) {
            BatchOperationResult result = new BatchOperationResult();
            result.setDeviceCode(deviceCode);
            try {
                DeviceInfoDTO device = getDeviceByCodeSafely(deviceCode);
                if (device == null) {
                    throw new JeecgBootBizTipException(ERR_DEVICE_NOT_FOUND);
                }
                updateTestFlag(device.getDeviceId(), true, null, operator, null);
                result.setSuccess(true);
            } catch (Exception e) {
                result.setSuccess(false);
                result.setMessage(e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    @Override
    public PageResult<DeviceLedgerDTO> listLedger(DeviceLedgerQuery query) {
        DeviceListQuery ssotQuery = new DeviceListQuery();
        ssotQuery.setPageNo(query.getPageNo());
        ssotQuery.setPageSize(query.getPageSize());
        ssotQuery.setTenantId(query.getTenantId());
        ssotQuery.setDeviceType(query.getDeviceType());
        ssotQuery.setDeviceModel(query.getDeviceModel());
        ssotQuery.setOnlineStatus(query.getOnlineStatus());
        ssotQuery.setTestDevice(query.getTestDevice());

        Result<PageResult<DeviceInfoDTO>> ssotResult = deviceInfoFeignClient.list(ssotQuery);
        if (ssotResult == null || !ssotResult.isSuccess() || ssotResult.getResult() == null) {
            return new PageResult<>(Collections.emptyList(), 0, query.getPageNo(), query.getPageSize());
        }
        PageResult<DeviceInfoDTO> ssotPage = ssotResult.getResult();
        List<DeviceLedgerDTO> records = ssotPage.getRecords().stream().map(this::toLedgerDTO).collect(Collectors.toList());
        return new PageResult<>(records, ssotPage.getTotal(), ssotPage.getPageNo(), ssotPage.getPageSize());
    }

    @Override
    public DeviceLedgerDTO getLedgerDetail(String deviceId) {
        DeviceInfoDTO device = getDeviceSafely(deviceId);
        if (device == null) {
            throw new JeecgBootBizTipException(ERR_DEVICE_NOT_FOUND);
        }
        return toLedgerDTO(device);
    }

    @Override
    public PageResult<AuditLogDTO> queryAuditLogs(AuditLogQuery query) {
        Page<DeviceOperationAuditLog> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<DeviceOperationAuditLog> pageResult = auditLogMapper.selectPage(page, Wrappers.<DeviceOperationAuditLog>lambdaQuery()
                .eq(StringUtils.hasText(query.getDeviceId()), DeviceOperationAuditLog::getDeviceId, query.getDeviceId())
                .eq(StringUtils.hasText(query.getOperationType()), DeviceOperationAuditLog::getOperationType, query.getOperationType())
                .eq(StringUtils.hasText(query.getAuditLevel()), DeviceOperationAuditLog::getAuditLevel, query.getAuditLevel())
                .ge(query.getStartTime() != null, DeviceOperationAuditLog::getCreatedAt, query.getStartTime())
                .le(query.getEndTime() != null, DeviceOperationAuditLog::getCreatedAt, query.getEndTime())
                .orderByDesc(DeviceOperationAuditLog::getCreatedAt));

        List<AuditLogDTO> records = pageResult.getRecords().stream().map(this::toAuditLogDTO).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    // ------------------------------------------------------------------
    // 内部辅助方法
    // ------------------------------------------------------------------

    private void syncBindingSnapshot(String deviceId, List<String> boundDeviceIds) {
        BindingUpdateRequest request = new BindingUpdateRequest();
        request.setBoundDeviceIds(boundDeviceIds);
        Result<Void> result = deviceInfoFeignClient.updateBinding(deviceId, request);
        if (result == null || !result.isSuccess()) {
            log.warn("[device-mgmt] 绑定快照同步 SSOT 失败 deviceId={}，将由重试补偿（详见设备基座详细设计 3.6）", deviceId);
        }
    }

    private BindingDTO toBindingDTO(DeviceBinding binding) {
        BindingDTO dto = new BindingDTO();
        dto.setId(binding.getId());
        dto.setMasterDeviceId(binding.getMasterDeviceId());
        dto.setSlaveDeviceId(binding.getSlaveDeviceId());
        dto.setTenantId(binding.getTenantId());
        dto.setBindMode(binding.getBindMode());
        dto.setStatus(binding.getStatus());
        dto.setCreatedBy(binding.getCreatedBy());
        dto.setCreatedAt(binding.getCreatedAt());
        return dto;
    }

    private DeviceLedgerDTO toLedgerDTO(DeviceInfoDTO device) {
        DeviceLedgerDTO dto = new DeviceLedgerDTO();
        dto.setDevice(device);

        DeviceCredential credential = credentialMapper.selectById(device.getDeviceId());
        if (credential == null || credential.getTokenJti() == null) {
            dto.setTokenStatus("NONE");
        } else if (credential.getTokenRevokedAt() != null) {
            dto.setTokenStatus("REVOKED");
        } else if (credential.getTokenExpiresAt() != null && credential.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            dto.setTokenStatus("EXPIRED");
        } else {
            dto.setTokenStatus("ACTIVE");
        }
        dto.setDeviceSecretVersion(credential == null ? null : credential.getDeviceSecretVersion());
        dto.setTokenExpiresAt(credential == null ? null : credential.getTokenExpiresAt());

        List<DeviceBinding> bindings = bindingMapper.selectList(Wrappers.<DeviceBinding>lambdaQuery()
                .and(w -> w.eq(DeviceBinding::getMasterDeviceId, device.getDeviceId())
                        .or().eq(DeviceBinding::getSlaveDeviceId, device.getDeviceId()))
                .eq(DeviceBinding::getStatus, "ACTIVE"));
        dto.setBindings(bindings.stream().map(this::toBindingDTO).collect(Collectors.toList()));
        return dto;
    }

    private AuditLogDTO toAuditLogDTO(DeviceOperationAuditLog entry) {
        AuditLogDTO dto = new AuditLogDTO();
        dto.setId(entry.getId());
        dto.setDeviceId(entry.getDeviceId());
        dto.setOperationType(entry.getOperationType());
        dto.setOperator(entry.getOperator());
        dto.setOperatorTenantId(entry.getOperatorTenantId());
        dto.setTargetTenantId(entry.getTargetTenantId());
        dto.setAuditLevel(entry.getAuditLevel());
        dto.setCreatedAt(entry.getCreatedAt());
        if (StringUtils.hasText(entry.getDetail())) {
            try {
                dto.setDetail(objectMapper.readValue(entry.getDetail(), new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception e) {
                log.warn("[device-mgmt] 审计日志 detail 反序列化失败 id={}", entry.getId());
            }
        }
        return dto;
    }

    private void writeAudit(String deviceId, String operationType, String operator, String operatorTenantId,
                             String targetTenantId, String auditLevel, Object detail) {
        DeviceOperationAuditLog entry = new DeviceOperationAuditLog();
        entry.setId(IdUtil.fastSimpleUUID());
        entry.setDeviceId(deviceId);
        entry.setOperationType(operationType);
        entry.setOperator(operator);
        entry.setOperatorTenantId(operatorTenantId);
        entry.setTargetTenantId(targetTenantId);
        entry.setAuditLevel(auditLevel);
        try {
            entry.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[device-mgmt] 审计详情序列化失败 operationType={}: {}", operationType, e.getMessage());
        }
        auditLogMapper.insert(entry);
    }

    private String targetTenantIdOf(String deviceId) {
        DeviceInfoDTO device = getDeviceSafely(deviceId);
        return device == null ? null : device.getTenantId();
    }

    private DeviceInfoDTO getDeviceSafely(String deviceId) {
        try {
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDevice(deviceId);
            return result != null && result.isSuccess() ? result.getResult() : null;
        } catch (Exception e) {
            log.debug("[device-mgmt] 设备信息基础服务查询未命中或不可用 deviceId={}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    private DeviceInfoDTO getDeviceByCodeSafely(String deviceCode) {
        try {
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDeviceByCode(deviceCode);
            return result != null && result.isSuccess() ? result.getResult() : null;
        } catch (Exception e) {
            log.debug("[device-mgmt] 设备信息基础服务查询未命中或不可用 deviceCode={}: {}", deviceCode, e.getMessage());
            return null;
        }
    }
}
