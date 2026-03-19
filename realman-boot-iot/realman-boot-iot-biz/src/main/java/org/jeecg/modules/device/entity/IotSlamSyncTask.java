package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("iot_slam_sync_task")
public class IotSlamSyncTask implements Serializable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("enterprise_id")
    private String enterpriseId;
    @TableField("source_robot_id")
    private String sourceRobotId;
    @TableField("source_robot_code")
    private String sourceRobotCode;
    @TableField("slam_map_id")
    private String slamMapId;
    @TableField("target_robot_ids")
    private String targetRobotIds;
    @TableField("total_count")
    private Integer totalCount;
    @TableField("success_count")
    private Integer successCount;
    @TableField("fail_count")
    private Integer failCount;
    @TableField("status")
    private Integer status;
    @TableField("create_by")
    private String createBy;
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

