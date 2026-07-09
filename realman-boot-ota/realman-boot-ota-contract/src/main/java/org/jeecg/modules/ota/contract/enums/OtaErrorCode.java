package org.jeecg.modules.ota.contract.enums;

/**
 * OTA 平台细化错误码常量，逐字对齐 OTA 平台详细设计第九章（PRD 4.6.2，30 个）。
 * {@code upgrade_error_code}/{@code upgrade_error_msg} 须同时上报，不允许只返回
 * 通用"升级失败"。
 */
public final class OtaErrorCode {

    private OtaErrorCode() {
    }

    public static final String ERR_DOWNLOAD_FAILED = "ERR_DOWNLOAD_FAILED";
    public static final String ERR_CHECKSUM_MISMATCH = "ERR_CHECKSUM_MISMATCH";
    public static final String ERR_SIGNATURE_INVALID = "ERR_SIGNATURE_INVALID";
    public static final String ERR_KEY_REVOKED = "ERR_KEY_REVOKED";
    public static final String ERR_EXTRACT_FAILED = "ERR_EXTRACT_FAILED";
    public static final String ERR_INSTALL_FAILED = "ERR_INSTALL_FAILED";
    public static final String ERR_HEALTH_CHECK_FAILED = "ERR_HEALTH_CHECK_FAILED";
    public static final String ERR_HEALTH_CHECK_TIMEOUT = "ERR_HEALTH_CHECK_TIMEOUT";
    public static final String ERR_ROLLBACK_FAILED = "ERR_ROLLBACK_FAILED";
    public static final String ERR_ROLLBACK_IN_PROGRESS = "ERR_ROLLBACK_IN_PROGRESS";
    public static final String ERR_STATUS_REPORT_FAILED = "ERR_STATUS_REPORT_FAILED";
    public static final String ERR_PRECONDITION_FAILED = "ERR_PRECONDITION_FAILED";
    public static final String ERR_RESOURCE_INSUFFICIENT = "ERR_RESOURCE_INSUFFICIENT";
    public static final String ERR_VERSION_INCOMPATIBLE = "ERR_VERSION_INCOMPATIBLE";
    public static final String ERR_DUPLICATE_TASK = "ERR_DUPLICATE_TASK";
    public static final String ERR_HIGH_RISK_RESTRICTED = "ERR_HIGH_RISK_RESTRICTED";
    public static final String ERR_URL_EXPIRED = "ERR_URL_EXPIRED";
    public static final String ERR_BATCH_THRESHOLD_EXCEEDED = "ERR_BATCH_THRESHOLD_EXCEEDED";
    public static final String ERR_CONFIG_CONFLICT = "ERR_CONFIG_CONFLICT";
    public static final String ERR_SIG_FILE_MISSING = "ERR_SIG_FILE_MISSING";
    public static final String ERR_SIG_FORMAT_INVALID = "ERR_SIG_FORMAT_INVALID";
    public static final String ERR_PENDING_DISPATCH_TIMEOUT = "ERR_PENDING_DISPATCH_TIMEOUT";
    public static final String ERR_FIRMWARE_TOO_LARGE = "ERR_FIRMWARE_TOO_LARGE";
    public static final String ERR_BATCH_DEVICE_LIMIT_EXCEEDED = "ERR_BATCH_DEVICE_LIMIT_EXCEEDED";
    public static final String ERR_INVALID_VERSION_FORMAT = "ERR_INVALID_VERSION_FORMAT";
    public static final String ERR_NOT_CANCELABLE = "ERR_NOT_CANCELABLE";
    public static final String ERR_INVALID_STATE = "ERR_INVALID_STATE";

    /** 复用设备基座既有语义（见设备管理业务平台 Token 校验），OTA 平台自身不重复定义校验逻辑，仅收录常量供错误码统一展示。 */
    public static final String ERR_TOKEN_REVOKED = "ERR_TOKEN_REVOKED";
    public static final String ERR_DEVICE_NOT_AUTHORIZED = "ERR_DEVICE_NOT_AUTHORIZED";

    /** 设备基座待实现的频率限制错误码，见 OTA 平台详细设计第七章"尚未实现、需要补的差距"。 */
    public static final String ERR_REGISTER_RATE_LIMIT = "ERR_REGISTER_RATE_LIMIT";
    public static final String ERR_SECRET_GENERATE_RATE_LIMIT = "ERR_SECRET_GENERATE_RATE_LIMIT";
}
