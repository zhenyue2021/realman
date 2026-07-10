package org.jeecg.modules.ota.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 固件包。对齐 OTA 平台详细设计 3.2（PRD 4.2.1）。固件包不区分租户归属。
 * JSON 字段（compatible_models）落库为字符串，手动做 Jackson 序列化，与
 * 设备基座 DeviceInfo 实体的既有约定一致。
 */
@Data
@TableName("ota_firmware")
public class OtaFirmware implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "package_id", type = IdType.INPUT)
    private String packageId;

    @TableField("firmware_file_name")
    private String firmwareFileName;

    /** master / slave */
    @TableField("device_type")
    private String deviceType;

    @TableField("version")
    private String version;

    @TableField("min_version")
    private String minVersion;

    /** JSON 字符串数组，为空数组视为全型号 */
    @TableField("compatible_models")
    private String compatibleModels;

    @TableField("install_command")
    private String installCommand;

    @TableField("rollback_command")
    private String rollbackCommand;

    @TableField("healthcheck_command")
    private String healthcheckCommand;

    /** normal / high_risk */
    @TableField("risk_level")
    private String riskLevel;

    @TableField("cancelable_in_executing")
    private Boolean cancelableInExecuting;

    @TableField("sha256")
    private String sha256;

    @TableField("sig_oss_path")
    private String sigOssPath;

    @TableField("sig_local_path")
    private String sigLocalPath;

    @TableField("key_id")
    private String keyId;

    /** LOCAL / OSS */
    @TableField("storage_source")
    private String storageSource;

    @TableField("download_url")
    private String downloadUrl;

    /** OSS 预签名 URL 到期时间；LOCAL 存储恒为空（本地路径无过期概念） */
    @TableField("download_url_expires_at")
    private LocalDateTime downloadUrlExpiresAt;

    @TableField("file_size_mb")
    private Integer fileSizeMb;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
