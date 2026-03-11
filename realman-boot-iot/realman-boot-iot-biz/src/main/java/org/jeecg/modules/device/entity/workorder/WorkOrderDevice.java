package org.jeecg.modules.device.entity.workorder;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单绑定设备
 */
@Data
@TableName("work_order_device")
public class WorkOrderDevice implements Serializable {

    @TableId
    private String id;

    @TableField("work_order_id")
    private String workOrderId;

    @TableField("device_type")
    private String deviceType; // CONTROLLER / ROBOT

    @TableField("device_id")
    private String deviceId;

    @TableField("device_name")
    private String deviceName;

    @TableField("device_code")
    private String deviceCode;

    @TableField("actual_device_id")
    private String actualDeviceId;

    @TableField("actual_device_name")
    private String actualDeviceName;

    @TableField("actual_device_code")
    private String actualDeviceCode;

    @TableField("create_time")
    private LocalDateTime createTime;
}

