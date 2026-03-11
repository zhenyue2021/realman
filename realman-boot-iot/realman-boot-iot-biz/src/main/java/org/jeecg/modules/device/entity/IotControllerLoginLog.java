package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 主控端登录记录：操作员登录主控设备时记录登录信息及当时关联的机器人设备
 */
@Data
@TableName("iot_controller_login_log")
public class IotControllerLoginLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** 主控设备ID（iot_device.id） */
    @TableField("controller_id")
    private String controllerId;

    /** 主控设备编码（device_code） */
    @TableField("controller_code")
    private String controllerCode;

    /** 操作员ID（如 sys_user.id） */
    @TableField("operator_id")
    private String operatorId;

    /** 操作员账号/姓名 */
    @TableField("operator_name")
    private String operatorName;

    /** 当时关联的机器人设备ID */
    @TableField("associated_robot_id")
    private String associatedRobotId;

    /** 当时关联的机器人设备编码 */
    @TableField("associated_robot_code")
    private String associatedRobotCode;

    /** 登录时间 */
    @TableField("login_time")
    private LocalDateTime loginTime;

    @TableField("create_time")
    private LocalDateTime createTime;
}
