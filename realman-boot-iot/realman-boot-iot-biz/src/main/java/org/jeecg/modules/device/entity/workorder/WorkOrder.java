package org.jeecg.modules.device.entity.workorder;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工单主实体
 */
@Data
@TableName("work_order")
public class WorkOrder implements Serializable {

    @TableId
    private String id;

    @TableField("task_name")
    private String taskName;

    @TableField("agent_id")
    private String agentId;

    @TableField("agent_name")
    private String agentName;

    @TableField("department_id")
    private String departmentId;

    @TableField("department_name")
    private String departmentName;

    @TableField("compliance_id")
    private String complianceId;

    @TableField("currency")
    private String currency;

    @TableField("unit_price")
    private BigDecimal unitPrice;

    @TableField("total_price")
    private BigDecimal totalPrice;

    @TableField("remark")
    private String remark;

    @TableField("plan_start_time")
    private LocalDateTime planStartTime;

    @TableField("plan_end_time")
    private LocalDateTime planEndTime;

    @TableField("status")
    private String status;

    @TableField("audit_result")
    private String auditResult;

    @TableField("operator_id")
    private String operatorId;

    @TableField("operator_name")
    private String operatorName;

    @TableField("operator_phone")
    private String operatorPhone;

    @TableField("actual_start_time")
    private LocalDateTime actualStartTime;

    @TableField("submit_time")
    private LocalDateTime submitTime;

    @TableField("timeout_reason")
    private String timeoutReason;

    @TableField("timeout_reason_source")
    private String timeoutReasonSource;

    @TableField("audit_by")
    private String auditBy;

    @TableField("audit_time")
    private LocalDateTime auditTime;

    @TableField("audit_comment")
    private String auditComment;

    @TableField("close_by")
    private String closeBy;

    @TableField("close_time")
    private LocalDateTime closeTime;

    @TableField("close_reason")
    private String closeReason;

    @TableField("create_by")
    private String createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField("update_by")
    private String updateBy;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("del_flag")
    private Integer delFlag;

    @TableField("tenant_id")
    private String tenantId;
}

