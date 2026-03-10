package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备授权关系表（ACL）
 * 一条记录代表：某个主体(subject) 在某时间段内对某台设备拥有权限
 */
@Data
@TableName("iot_device_auth")
public class IotDeviceAuth implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 授权主体类型：
     * USER  - 按用户账号授权（subjectId = username 或 userId）
     * TENANT- 按租户授权（subjectId = sys_tenant.id）
     * 预留：COMPANY 等
     */
    @TableField("subject_type")
    private String subjectType;

    /** 授权主体ID（与 subjectType 搭配使用） */
    @TableField("subject_id")
    private String subjectId;

    /** 主控端ID（控制端标识，可与deviceCode对应） */
    @TableField("controller_id")
    private String controllerId;

    /** 设备ID（iot_device.id） */
    @TableField("device_id")
    private String deviceId;

    /** 设备编码（冗余） */
    @TableField("device_code")
    private String deviceCode;

    /** 管理后台账号ID（sys_user.id） */
    @TableField("admin_user_id")
    private String adminUserId;

    /** 管理后台账号用户名（sys_user.username，冗余） */
    @TableField("admin_username")
    private String adminUsername;

    /** 生效时间 */
    @TableField("effective_time")
    private LocalDateTime effectiveTime;

    /** 失效时间 */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /** 授权状态：1 启用 0 禁用 */
    @TableField("status")
    private Integer status;

    @TableField(value = "create_by")
    private String createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_by")
    private String updateBy;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("del_flag")
    private Integer delFlag;
}

