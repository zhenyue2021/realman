package org.jeecg.modules.devicemgmt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备双凭证体系。对齐设备基座详细设计 3.2/3.3：
 * {@code deviceSecretHash} 是 MQTT 连接层密码（哈希存储），{@code tokenJti}/
 * {@code tokenExpiresAt}/{@code tokenRevokedAt} 是业务身份 Token（JWT）的生命周期状态。
 */
@Data
@TableName("device_credential")
public class DeviceCredential implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 关联 device_info.device_id，同时作为本表主键。 */
    @TableId(value = "device_id", type = IdType.INPUT)
    private String deviceId;

    @TableField("device_secret_hash")
    private String deviceSecretHash;

    @TableField("device_secret_version")
    private Integer deviceSecretVersion;

    @TableField("token_jti")
    private String tokenJti;

    @TableField("token_issued_at")
    private LocalDateTime tokenIssuedAt;

    @TableField("token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @TableField("token_revoked_at")
    private LocalDateTime tokenRevokedAt;

    @TableField("token_revoke_reason")
    private String tokenRevokeReason;
}
