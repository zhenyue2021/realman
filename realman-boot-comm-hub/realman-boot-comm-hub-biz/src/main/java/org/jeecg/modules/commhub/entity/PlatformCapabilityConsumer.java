package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("platform_capability_consumer")
public class PlatformCapabilityConsumer implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("capability_code")
    private String capabilityCode;
    @TableField("consumer_type")
    private String consumerType;
    @TableField("consumer_id")
    private String consumerId;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("allowed_scope")
    private String allowedScope;
    @TableField("quota_policy")
    private String quotaPolicy;
    @TableField("status")
    private String status;
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
