package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("iot_robot_slam_binding")
public class IotRobotSlamBinding implements Serializable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("enterprise_id")
    private String enterpriseId;
    @TableField("robot_id")
    private String robotId;
    @TableField("robot_code")
    private String robotCode;
    @TableField("slam_map_id")
    private String slamMapId;
    @TableField("state")
    private Integer state;
    @TableField("pending_task_id")
    private String pendingTaskId;
    @TableField("effective_time")
    private LocalDateTime effectiveTime;
    @TableField("obsolete_time")
    private LocalDateTime obsoleteTime;
    @TableField("fail_reason")
    private String failReason;
    @TableField("create_by")
    private String createBy;
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    @TableField("del_flag")
    private Integer delFlag;
}

