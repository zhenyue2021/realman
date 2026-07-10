package org.jeecg.modules.ota.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 17 项系统设置的键名与默认值，对齐 OTA 平台详细设计第十章（PRD 9.9）。
 * {@code ota_system_setting} 表缺失某键时，{@code OtaSystemSettingService} 按此兜底。
 */
public final class OtaSystemSettingDefaults {

    private OtaSystemSettingDefaults() {
    }

    public static final String DISK_VALID_SECONDS = "disk_valid_seconds";
    public static final String MEMORY_VALID_SECONDS = "memory_valid_seconds";
    public static final String POWER_VALID_SECONDS = "power_valid_seconds";
    public static final String NETWORK_VALID_SECONDS = "network_valid_seconds";
    public static final String PENDING_URL_CHECK_INTERVAL_MINUTES = "pending_url_check_interval_minutes";
    public static final String OSS_URL_EXPIRY_SECONDS = "oss_url_expiry_seconds";
    public static final String POLL_INTERVAL_SECONDS = "poll_interval_seconds";
    public static final String PUSH_EXEMPT_SECONDS = "push_exempt_seconds";
    public static final String DEVICE_OFFLINE_TIMEOUT_HOURS = "device_offline_timeout_hours";
    public static final String PENDING_ONLINE_DEVICE_TIMEOUT_MINUTES = "pending_online_device_timeout_minutes";
    public static final String CANCEL_ACK_TIMEOUT_SECONDS = "cancel_ack_timeout_seconds";
    public static final String DEVICE_TOKEN_EXPIRY_DAYS = "device_token_expiry_days";
    public static final String REGISTRATION_SECRET_EXPIRY_DAYS = "registration_secret_expiry_days";
    public static final String DEFAULT_FAIL_THRESHOLD_TYPE = "default_fail_threshold_type";
    public static final String DEFAULT_FAIL_THRESHOLD = "default_fail_threshold";
    public static final String DEFAULT_ON_THRESHOLD_EXCEEDED = "default_on_threshold_exceeded";
    public static final String HEARTBEAT_INTERVAL_SECONDS = "heartbeat_interval_seconds";

    /** 未纳入 9.9 节交叉校验清单，但同样属于系统设置的字段 */
    public static final String MAX_FIRMWARE_SIZE_MB = "max_firmware_size_mb";
    public static final String MAX_BATCH_DEVICES = "max_batch_devices";
    public static final String GLOBAL_SIG_VERIFY_ENABLED = "global_sig_verify_enabled";

    /** 下发失败自动重试扫描（本轮新增，弥补"下行发布失败后子任务永久卡在 STARTING"的已知缺口） */
    public static final String DISPATCH_MAX_ATTEMPTS = "dispatch_max_attempts";
    public static final String DISPATCH_RETRY_INTERVAL_SECONDS = "dispatch_retry_interval_seconds";

    /**
     * 版本落后 warn/critical 判定阈值（本轮新增，弥补此前硬编码在
     * OtaVersionMatrixServiceImpl 里的已知限制）。大版本号落后 &gt;=1 恒为 critical，
     * 视为结构性规则不纳入配置；只有小版本号落后的 warn/critical 分界可配置。
     */
    public static final String VERSION_LAG_WARN_MINOR_DIFF = "version_lag_warn_minor_diff";
    public static final String VERSION_LAG_CRITICAL_MINOR_DIFF = "version_lag_critical_minor_diff";

    public static final Map<String, String> DEFAULTS = defaults();

    private static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(DISK_VALID_SECONDS, "300");
        map.put(MEMORY_VALID_SECONDS, "300");
        map.put(POWER_VALID_SECONDS, "300");
        map.put(NETWORK_VALID_SECONDS, "300");
        map.put(PENDING_URL_CHECK_INTERVAL_MINUTES, "15");
        map.put(OSS_URL_EXPIRY_SECONDS, "86400");
        map.put(POLL_INTERVAL_SECONDS, "30");
        map.put(PUSH_EXEMPT_SECONDS, "30");
        map.put(DEVICE_OFFLINE_TIMEOUT_HOURS, "72");
        map.put(PENDING_ONLINE_DEVICE_TIMEOUT_MINUTES, "30");
        map.put(CANCEL_ACK_TIMEOUT_SECONDS, "60");
        map.put(DEVICE_TOKEN_EXPIRY_DAYS, "365");
        map.put(REGISTRATION_SECRET_EXPIRY_DAYS, "365");
        map.put(DEFAULT_FAIL_THRESHOLD_TYPE, "count");
        map.put(DEFAULT_FAIL_THRESHOLD, "5");
        map.put(DEFAULT_ON_THRESHOLD_EXCEEDED, "pause");
        map.put(HEARTBEAT_INTERVAL_SECONDS, "60");
        map.put(MAX_FIRMWARE_SIZE_MB, "2048");
        map.put(MAX_BATCH_DEVICES, "1000");
        map.put(GLOBAL_SIG_VERIFY_ENABLED, "true");
        map.put(DISPATCH_MAX_ATTEMPTS, "3");
        map.put(DISPATCH_RETRY_INTERVAL_SECONDS, "60");
        map.put(VERSION_LAG_WARN_MINOR_DIFF, "2");
        map.put(VERSION_LAG_CRITICAL_MINOR_DIFF, "5");
        return map;
    }
}
