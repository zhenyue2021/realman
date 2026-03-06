package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("iot_device_config")
public class IotDeviceConfig implements Serializable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("device_id")
    private String deviceId;
    @TableField("device_code")
    private String deviceCode;
    @TableField("config_key")
    private String configKey;
    @TableField("config_value")
    private String configValue;
    @TableField("config_type")
    private String configType;
    @TableField("sync_status")
    private Integer syncStatus;
    @TableField("sync_time")
    private LocalDateTime syncTime;
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
