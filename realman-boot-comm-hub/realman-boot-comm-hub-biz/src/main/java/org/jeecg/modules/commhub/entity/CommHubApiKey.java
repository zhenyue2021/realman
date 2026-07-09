package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * HTTP-MQTT 桥接的第三方系统身份，对齐设备通信中台详细设计 4.3.1/4.5：每个 API Key
 * 绑定可操作的设备范围（{@code deviceScope}）与可下发的 Topic 后缀范围
 * （{@code topicSuffixScope}），防止第三方越权向不属于自己的设备/Topic 下发指令。
 */
@Data
@TableName("comm_hub_api_key")
public class CommHubApiKey implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("tenant_id")
    private String tenantId;

    /** SHA-256(原始 Key)，原始 Key 只在创建时返回一次，不落库明文。 */
    @TableField("api_key_hash")
    private String apiKeyHash;

    /** 原始 Key 前 8 位，仅供台账辨识。 */
    @TableField("key_prefix")
    private String keyPrefix;

    /** 逗号分隔 deviceId 列表，{@code *} 表示不限设备（仍受 tenant_id 约束）。 */
    @TableField("device_scope")
    private String deviceScope;

    /** 逗号分隔 topicSuffix（支持 {@code xxx/*} 前缀通配），{@code *} 表示不限 Topic。 */
    @TableField("topic_suffix_scope")
    private String topicSuffixScope;

    /** ACTIVE / REVOKED */
    @TableField("status")
    private String status;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
