package org.jeecg.modules.device.entity.workorder;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单合规配置实体
 */
@Data
@TableName("work_order_compliance_config")
public class WorkOrderComplianceConfig implements Serializable {

    @TableId
    private String id;

    @TableField("agent_id")
    private String agentId;

    @TableField("agent_name")
    private String agentName;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("enterprise_name")
    private String enterpriseName;

    @TableField("task_scene")
    private String taskScene;

    @TableField("timeout_alert_enabled")
    private Integer timeoutAlertEnabled;

    @TableField("timeout_alert_offset")
    private String timeoutAlertOffset;

    @TableField("task_limit_enabled")
    private Integer taskLimitEnabled;

    @TableField("acceptance_enabled")
    private Integer acceptanceEnabled;

    @TableField("overtime_reason_enum")
    private String overtimeReasonEnum;

    @TableField("overtime_reason_desc")
    private String overtimeReasonDesc;

    @TableField("auto_close_enabled")
    private Integer autoCloseEnabled;

    @TableField("auto_close_offset")
    private String autoCloseOffset;

    @TableField("apply_status")
    private Integer applyStatus;

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

