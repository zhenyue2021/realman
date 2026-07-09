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
 * 主控端 ↔ 机器人授权绑定，权威记录。对齐设备基座详细设计 3.2/3.4：
 * V1 一对一，V2 多对多；变更后本层负责同步一份快照到 SSOT
 * （见 {@code BindingUpdateRequest}），SSOT 侧只读该快照。
 */
@Data
@TableName("device_binding")
public class DeviceBinding implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("master_device_id")
    private String masterDeviceId;

    @TableField("slave_device_id")
    private String slaveDeviceId;

    @TableField("tenant_id")
    private String tenantId;

    /** V1_ONE_TO_ONE / V2_MANY_TO_MANY */
    @TableField("bind_mode")
    private String bindMode;

    /** ACTIVE / REVOKED */
    @TableField("status")
    private String status;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
