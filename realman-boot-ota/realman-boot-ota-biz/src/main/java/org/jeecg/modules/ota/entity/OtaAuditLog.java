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
 * OTA 操作审计日志，对齐 OTA 平台详细设计十三章（PRD 4.8）。
 */
@Data
@TableName("ota_audit_log")
public class OtaAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** UPLOAD_FIRMWARE/DELETE_FIRMWARE/KEY_UPLOAD/KEY_ACTIVATE/KEY_REVOKE/SIG_VERIFY_TOGGLE/
     * TASK_CREATE/TASK_RETRY/TASK_CANCEL/TASK_ROLLBACK/TASK_RESUME/TASK_ABORT/... */
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

    @TableField("task_id")
    private String taskId;

    @TableField("package_id")
    private String packageId;

    @TableField("key_id")
    private String keyId;

    /** 操作详情快照（JSON 字符串） */
    @TableField("detail")
    private String detail;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
