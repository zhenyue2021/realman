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
 * 操作审计日志。对齐设备基座详细设计 3.2：全部写操作留痕，跨租户操作
 * {@code operatorTenantId} 与 {@code targetTenantId} 不同即视为超管跨租户操作。
 * {@code detail} 落库为 JSON 字符串（与 {@code DeviceInfo} 的 JSON 字段同样约定，
 * 由 biz 内手动做 Jackson 序列化，不引入 MyBatis-Plus JSON TypeHandler）。
 */
@Data
@TableName("device_operation_audit_log")
public class DeviceOperationAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** 部分操作（如凭证生成）可能未绑定具体设备 */
    @TableField("device_id")
    private String deviceId;

    /** REGISTER/TOKEN_ISSUE/TOKEN_REVOKE/TEST_FLAG/TENANT_AUTH/BINDING/LIFECYCLE_CHANGE/... */
    @TableField("operation_type")
    private String operationType;

    @TableField("operator")
    private String operator;

    @TableField("operator_tenant_id")
    private String operatorTenantId;

    @TableField("target_tenant_id")
    private String targetTenantId;

    /** normal / high / critical */
    @TableField("audit_level")
    private String auditLevel;

    /** 操作详情快照（JSON 字符串） */
    @TableField("detail")
    private String detail;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
