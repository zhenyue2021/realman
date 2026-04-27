package org.jeecg.modules.device.darwin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("darwin_workorder_mapping")
public class DarwinWorkOrderMapping implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @TableField("work_order_id")
    private String workOrderId;

    @TableField("darwin_order_id")
    private String darwinOrderId;

    @TableField("darwin_agent_id")
    private String darwinAgentId;

    @TableField("darwin_agent_name")
    private String darwinAgentName;

    @TableField("darwin_dept_id")
    private String darwinDeptId;

    @TableField("darwin_dept_name")
    private String darwinDeptName;

    /** 原始消息体，保留用于问题排查 */
    @TableField("raw_message")
    private String rawMessage;

    @TableLogic
    @TableField("del_flag")
    private Integer delFlag;

    @TableField("create_by")
    private String createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField("update_by")
    private String updateBy;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
