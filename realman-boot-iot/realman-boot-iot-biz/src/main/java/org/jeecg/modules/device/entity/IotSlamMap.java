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
@TableName("iot_slam_map")
public class IotSlamMap implements Serializable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("enterprise_id")
    private String enterpriseId;
    @TableField("map_name")
    private String mapName;
    @TableField("map_version")
    private String mapVersion;
    @TableField("source_robot_id")
    private String sourceRobotId;
    @TableField("source_robot_code")
    private String sourceRobotCode;
    @TableField("file_object_key")
    private String fileObjectKey;
    @TableField("file_md5")
    private String fileMd5;
    @TableField("file_size")
    private Long fileSize;
    @TableField("status")
    private Integer status;
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

