package org.jeecg.modules.ota.contract.enums;

/**
 * 升级方式，对齐 OTA 平台详细设计 5.1（PRD 4.4.1）。{@code high_risk} 固件包只允许
 * {@code BY_SN}，其余三种方式一律拒绝下发。
 */
public enum UpgradeMode {
    BY_SN,
    BY_MODEL,
    ALL,
    BY_TENANT_MODEL
}
