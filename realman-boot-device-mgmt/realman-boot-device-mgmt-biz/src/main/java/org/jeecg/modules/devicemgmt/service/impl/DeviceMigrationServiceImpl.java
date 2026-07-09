package org.jeecg.modules.devicemgmt.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOccupancyEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOnlineEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceRegisterWriteRequest;
import org.jeecg.modules.deviceinfo.contract.dto.FirmwareVersionUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.LifecycleUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyState;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;
import org.jeecg.modules.devicemgmt.entity.DeviceCredential;
import org.jeecg.modules.devicemgmt.entity.DeviceOperationAuditLog;
import org.jeecg.modules.devicemgmt.mapper.DeviceCredentialMapper;
import org.jeecg.modules.devicemgmt.mapper.DeviceOperationAuditLogMapper;
import org.jeecg.modules.devicemgmt.service.IDeviceMigrationService;
import org.jeecg.modules.devicemgmt.vo.LegacyDeviceMigrationResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IDeviceMigrationService} 实现。只在装配了 {@code legacyIotJdbcTemplate}
 * （即 {@code realman.migration.legacy-iot.enabled=true}）时才会被 Spring 创建，
 * 见 {@code DeviceMigrationController} 里对该 Bean 缺失时的降级处理。
 *
 * <p>迁移决策（已与产品/运维确认，见 2026-07 存量设备迁移评审）：
 * <ul>
 *   <li>所有存量设备统一分配 {@code tenant_id=1000}（当前系统里唯一的真实租户）；</li>
 *   <li>旧 {@code device_secret}（{@code md5(deviceCode)} 弱方案）暂按现值哈希落到
 *       {@code device_credential.device_secret_hash}，不强制设备侧改动；后续需要单独
 *       排期做强制轮换；</li>
 *   <li>{@code status=3}（禁用）与 {@code del_flag=1}（软删除）的设备都迁移，
 *       尽量保留历史，分别映射为 {@code lifecycle_stage=MAINTENANCE}/{@code RETIRED}。</li>
 * </ul>
 *
 * <p>已知范围限制：只迁移 {@code iot_device} → {@code device_info}/{@code device_credential}。
 * {@code iot_device_auth}（主控-机器人授权配对）暂不自动迁移到 {@code device_binding}——
 * 两者语义并不完全对应（前者是"某个管理员在某个时间窗口内被授权操作某对设备"，
 * 后者是"两台设备当前是否处于遥操配对状态"），需要产品单独确认映射规则后再补，
 * 避免把语义不对的数据当成"当前生效绑定"误用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "realman.migration.legacy-iot", name = "enabled", havingValue = "true")
public class DeviceMigrationServiceImpl implements IDeviceMigrationService {

    private static final String FIXED_TENANT_ID = "1000";
    private static final int MAX_FAILURE_DETAILS = 200;
    private static final String CONFIRM_TEXT = "MIGRATE_LEGACY_DEVICES";

    @Qualifier("legacyIotJdbcTemplate")
    private final JdbcTemplate legacyIotJdbcTemplate;

    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final DeviceCredentialMapper credentialMapper;
    private final DeviceOperationAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public LegacyDeviceMigrationResult migrateFromLegacyIot(String confirmText, String operator) {
        if (!CONFIRM_TEXT.equals(confirmText)) {
            throw new JeecgBootBizTipException("ERR_CONFIRM_TEXT_MISMATCH");
        }
        LegacyDeviceMigrationResult result = new LegacyDeviceMigrationResult();

        List<Map<String, Object>> rows = legacyIotJdbcTemplate.queryForList(
                "SELECT id, device_code, device_name, device_type, device_model, mac_address, "
                        + "firmware_version, status, use_status, device_secret, last_online_time, "
                        + "last_offline_time, create_time, update_time, del_flag "
                        + "FROM iot_device ORDER BY device_code, update_time DESC");
        result.setTotalScanned(rows.size());

        Map<String, List<Map<String, Object>>> byDeviceCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String deviceCode = String.valueOf(row.get("device_code"));
            byDeviceCode.computeIfAbsent(deviceCode, k -> new java.util.ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : byDeviceCode.entrySet()) {
            String deviceCode = entry.getKey();
            List<Map<String, Object>> group = entry.getValue();
            Map<String, Object> representative = pickRepresentative(group);

            for (Map<String, Object> row : group) {
                if (row != representative) {
                    writeHistoryAuditLog(deviceCode, row, operator);
                    result.setHistoryOnlyCount(result.getHistoryOnlyCount() + 1);
                }
            }

            try {
                if (deviceAlreadyMigrated(deviceCode)) {
                    result.setSkippedAlreadyExists(result.getSkippedAlreadyExists() + 1);
                    continue;
                }
                migrateDevice(deviceCode, representative, operator);
                result.setMigratedDeviceCount(result.getMigratedDeviceCount() + 1);
            } catch (Exception e) {
                log.warn("[device-mgmt] 存量设备迁移失败 deviceCode={}: {}", deviceCode, e.getMessage(), e);
                result.setFailedCount(result.getFailedCount() + 1);
                if (result.getFailureDetails().size() < MAX_FAILURE_DETAILS) {
                    result.getFailureDetails().add(deviceCode + ": " + e.getMessage());
                }
            }
        }

        writeAudit(null, "LEGACY_DEVICE_MIGRATION_RUN", operator, Map.of(
                "totalScanned", result.getTotalScanned(),
                "migratedDeviceCount", result.getMigratedDeviceCount(),
                "skippedAlreadyExists", result.getSkippedAlreadyExists(),
                "historyOnlyCount", result.getHistoryOnlyCount(),
                "failedCount", result.getFailedCount()));
        return result;
    }

    /** 同一 device_code 下优先取未软删除的一行；若全部软删除，取最近更新的一行。 */
    private Map<String, Object> pickRepresentative(List<Map<String, Object>> group) {
        for (Map<String, Object> row : group) {
            if (toInt(row.get("del_flag")) == 0) {
                return row;
            }
        }
        return group.get(0); // 已按 update_time DESC 排序，第一条即最近更新
    }

    private boolean deviceAlreadyMigrated(String deviceCode) {
        try {
            Result<org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO> existing =
                    deviceInfoFeignClient.getDeviceByCode(deviceCode);
            return existing != null && existing.isSuccess() && existing.getResult() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void migrateDevice(String deviceCode, Map<String, Object> row, String operator) {
        String deviceId = String.valueOf(row.get("id"));
        int legacyType = toInt(row.get("device_type"));
        int status = toInt(row.get("status"));
        int useStatus = toInt(row.get("use_status"));
        int delFlag = toInt(row.get("del_flag"));

        DeviceRegisterWriteRequest registerRequest = new DeviceRegisterWriteRequest();
        registerRequest.setDeviceId(deviceId);
        registerRequest.setDeviceCode(deviceCode);
        registerRequest.setDeviceType(legacyType == 2 ? DeviceType.MASTER : DeviceType.SLAVE);
        registerRequest.setTenantId(FIXED_TENANT_ID);
        registerRequest.setDeviceModel((String) row.get("device_model"));
        registerRequest.setDeviceName((String) row.get("device_name"));
        registerRequest.setMacAddress((String) row.get("mac_address"));
        Result<Void> registerResult = deviceInfoFeignClient.register(registerRequest);
        if (registerResult == null || !registerResult.isSuccess()) {
            throw new IllegalStateException("写入 device_info 失败："
                    + (registerResult == null ? "无响应" : registerResult.getMessage()));
        }

        LifecycleStage lifecycleStage;
        if (delFlag == 1) {
            lifecycleStage = LifecycleStage.RETIRED;
        } else if (status == 3) {
            lifecycleStage = LifecycleStage.MAINTENANCE;
        } else if (status == 0) {
            lifecycleStage = LifecycleStage.ACTIVATED;
        } else {
            lifecycleStage = LifecycleStage.RUNNING;
        }
        LifecycleUpdateRequest lifecycleRequest = new LifecycleUpdateRequest();
        lifecycleRequest.setLifecycleStage(lifecycleStage);
        deviceInfoFeignClient.updateLifecycle(deviceId, lifecycleRequest);

        LocalDateTime lastOnline = toLocalDateTime(row.get("last_online_time"));
        LocalDateTime lastOffline = toLocalDateTime(row.get("last_offline_time"));
        LocalDateTime updateTime = toLocalDateTime(row.get("update_time"));
        if (status == 1) {
            DeviceOnlineEventRequest onlineRequest = new DeviceOnlineEventRequest();
            onlineRequest.setDeviceId(deviceId);
            onlineRequest.setEventType(OnlineStatus.ONLINE);
            onlineRequest.setOccurredAt(lastOnline != null ? lastOnline : LocalDateTime.now());
            deviceInfoFeignClient.reportOnlineEvent(onlineRequest);

            DeviceOccupancyEventRequest occupancyRequest = new DeviceOccupancyEventRequest();
            occupancyRequest.setDeviceId(deviceId);
            occupancyRequest.setOccupancyState(useStatus == 1 ? OccupancyState.OCCUPIED : OccupancyState.IDLE);
            occupancyRequest.setOccupancyDetail(useStatus == 1 ? OccupancyDetail.LOCAL : null);
            occupancyRequest.setOccurredAt(lastOnline != null ? lastOnline : LocalDateTime.now());
            deviceInfoFeignClient.reportOccupancyEvent(occupancyRequest);
        } else if (status == 2 || status == 3) {
            DeviceOnlineEventRequest offlineRequest = new DeviceOnlineEventRequest();
            offlineRequest.setDeviceId(deviceId);
            offlineRequest.setEventType(OnlineStatus.OFFLINE);
            LocalDateTime occurredAt = lastOffline != null ? lastOffline : (updateTime != null ? updateTime : LocalDateTime.now());
            offlineRequest.setOccurredAt(occurredAt);
            offlineRequest.setOfflineReason(status == 3 ? "LEGACY_DISABLED" : "LEGACY_MIGRATION");
            deviceInfoFeignClient.reportOnlineEvent(offlineRequest);
        }
        // status == 0（未激活）：保留 register() 默认的 UNACTIVATED/OFFLINE，不追加事件

        String firmwareVersion = (String) row.get("firmware_version");
        if (StringUtils.hasText(firmwareVersion)) {
            FirmwareVersionUpdateRequest firmwareRequest = new FirmwareVersionUpdateRequest();
            firmwareRequest.setFirmwareVersion(firmwareVersion);
            deviceInfoFeignClient.updateFirmwareVersion(deviceId, firmwareRequest);
        }

        String legacySecret = (String) row.get("device_secret");
        DeviceCredential credential = new DeviceCredential();
        credential.setDeviceId(deviceId);
        credential.setDeviceSecretVersion(1);
        if (StringUtils.hasText(legacySecret)) {
            credential.setDeviceSecretHash(DigestUtil.sha256Hex(legacySecret));
        }
        credentialMapper.insert(credential);

        writeAudit(deviceId, "LEGACY_DEVICE_MIGRATED", operator, Map.of(
                "deviceCode", deviceCode,
                "legacyStatus", status,
                "legacyUseStatus", useStatus,
                "legacyDelFlag", delFlag,
                "lifecycleStage", lifecycleStage.name()));
    }

    private void writeHistoryAuditLog(String deviceCode, Map<String, Object> row, String operator) {
        Map<String, Object> detail = new LinkedHashMap<>();
        row.forEach((key, value) -> detail.put(key, jsonSafeValue(value)));
        detail.put("deviceCode", deviceCode);
        writeAudit(null, "LEGACY_DEVICE_HISTORY", operator, detail);
    }

    /** 原始 JDBC 行里的 Timestamp/LocalDateTime 无法被不带 JavaTimeModule 的 ObjectMapper 序列化，转成字符串。 */
    private Object jsonSafeValue(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toString();
        }
        return value;
    }

    private void writeAudit(String deviceId, String operationType, String operator, Object detail) {
        DeviceOperationAuditLog entry = new DeviceOperationAuditLog();
        entry.setId(IdUtil.fastSimpleUUID());
        entry.setDeviceId(deviceId);
        entry.setOperationType(operationType);
        entry.setOperator(operator);
        entry.setTargetTenantId(FIXED_TENANT_ID);
        entry.setAuditLevel("high");
        try {
            entry.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[device-mgmt] 迁移审计详情序列化失败 operationType={}: {}", operationType, e.getMessage());
        }
        auditLogMapper.insert(entry);
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
