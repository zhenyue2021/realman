package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("platform_capability")
public class PlatformCapability implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId("capability_code")
    private String capabilityCode;
    @TableField("capability_type")
    private String capabilityType;
    @TableField("provider_service")
    private String providerService;
    @TableField("version")
    private String version;
    @TableField("status")
    private String status;
    @TableField("auth_policy")
    private String authPolicy;
    @TableField("rate_limit_policy")
    private String rateLimitPolicy;
    @TableField("sla_level")
    private String slaLevel;
    @TableField("owner")
    private String owner;
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
