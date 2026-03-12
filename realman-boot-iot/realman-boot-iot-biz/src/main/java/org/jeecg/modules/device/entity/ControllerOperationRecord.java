package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 主控遥操操作记录：遥操员使用主控设备操控机器人完成工单的时间
 * 开始操作时间 = 工单开启时间；结束操作时间 = 正常提交的工单用提交时间，异常工单用工单失效时间
 */
@Data
@TableName("controller_operation_record")
public class ControllerOperationRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private String id;

    @TableField("controller_id")
    private String controllerId;

    @TableField("controller_code")
    private String controllerCode;

    @TableField("robot_id")
    private String robotId;

    @TableField("robot_code")
    private String robotCode;

    @TableField("operator_id")
    private String operatorId;

    @TableField("operator_name")
    private String operatorName;

    @TableField("work_order_id")
    private String workOrderId;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
