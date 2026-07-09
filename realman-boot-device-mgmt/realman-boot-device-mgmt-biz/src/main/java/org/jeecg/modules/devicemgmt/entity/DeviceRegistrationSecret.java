package org.jeecg.modules.devicemgmt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次性注册凭证。对齐设备基座详细设计 3.2、OTA PRD 9.8.5。
 *
 * <p>本轮只实现 {@code provision} 校验消费这张表的读路径；凭证的生成/查询 REST
 * （面向超管的管理端 API）留给后续补充，见本模块 pom.xml 说明。
 */
@Data
@TableName("device_registration_secret")
public class DeviceRegistrationSecret implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("device_code")
    private String deviceCode;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("secret_hash")
    private String secretHash;

    /** UNUSED / USED / EXPIRED */
    @TableField("status")
    private String status;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("used_at")
    private LocalDateTime usedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
