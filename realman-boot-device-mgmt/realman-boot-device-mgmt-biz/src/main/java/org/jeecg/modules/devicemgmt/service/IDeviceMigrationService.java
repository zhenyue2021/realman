package org.jeecg.modules.devicemgmt.service;

import org.jeecg.modules.devicemgmt.vo.LegacyDeviceMigrationResult;

/**
 * 存量设备一次性迁移：把旧 {@code realman-boot-iot} 单体的 {@code iot_device} 表投影
 * 迁移到新的 {@code device_info}（SSOT）+ {@code device_credential}（设备管理业务平台）。
 * 只在 {@code realman.migration.legacy-iot.enabled=true} 时可用，见
 * {@link org.jeecg.modules.devicemgmt.migration.LegacyIotDataSourceConfig}。
 */
public interface IDeviceMigrationService {

    /** {@code confirmText} 必须为 {@code MIGRATE_LEGACY_DEVICES}，否则 409 ERR_CONFIRM_TEXT_MISMATCH。 */
    LegacyDeviceMigrationResult migrateFromLegacyIot(String confirmText, String operator);
}
