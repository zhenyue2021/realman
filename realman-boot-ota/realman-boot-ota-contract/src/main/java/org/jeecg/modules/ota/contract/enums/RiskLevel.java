package org.jeecg.modules.ota.contract.enums;

/**
 * 固件包风险等级，对齐 OTA 平台详细设计 4.2.1/9.8.4 高风险管控规则：
 * {@code HIGH_RISK} 只允许通过 by_sn 下发给已标记 is_test_device=true 的设备。
 */
public enum RiskLevel {
    NORMAL,
    HIGH_RISK
}
