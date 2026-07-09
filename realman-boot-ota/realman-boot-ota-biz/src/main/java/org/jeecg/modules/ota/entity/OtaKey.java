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
 * OTA 签名公钥生命周期。对齐 OTA 平台详细设计四章（PRD 4.2.2）。私钥不入库，
 * 由固件发布方离线持有。
 */
@Data
@TableName("ota_key")
public class OtaKey implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "key_id", type = IdType.ASSIGN_ID)
    private String keyId;

    /** 固定 Ed25519 */
    @TableField("algorithm")
    private String algorithm;

    @TableField("public_key_pem")
    private String publicKeyPem;

    /** SHA-256 指纹前 32 位十六进制（128 bit） */
    @TableField("key_fingerprint")
    private String keyFingerprint;

    @TableField("key_alias")
    private String keyAlias;

    /** active / pending_activation / revoked */
    @TableField("status")
    private String status;

    @TableField("activated_at")
    private LocalDateTime activatedAt;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    @TableField("revoke_reason")
    private String revokeReason;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
