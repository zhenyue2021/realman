package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("iot_device")
public class IotDevice implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("device_code")
    private String deviceCode;
    @TableField("device_name")
    private String deviceName;
    /**
     * 1-网关 2-传感器 3-控制器
     */
    @TableField("device_type")
    private Integer deviceType;
    @TableField("product_id")
    private String productId;
    @TableField("device_model")
    private String deviceModel;
    @TableField("serial_number")
    private String serialNumber;
    @TableField("firmware_version")
    private String firmwareVersion;
    /**
     * 0-未激活 1-在线 2-离线 3-禁用
     */
    @TableField("status")
    private Integer status;
    /**
     * 64位Hex设备密钥，MQTT连接密码，同时派生AES Key
     */
    @TableField("device_secret")
    private String deviceSecret;
    @TableField("secret_create_time")
    private LocalDateTime secretCreateTime;
    @TableField("description")
    private String description;
    @TableField("last_online_time")
    private LocalDateTime lastOnlineTime;
    @TableField("last_offline_time")
    private LocalDateTime lastOfflineTime;
    @TableField("longitude")
    private BigDecimal longitude;
    @TableField("latitude")
    private BigDecimal latitude;
    @TableField("create_by")
    private String createBy;
    @TableField("tenant_id")
    private Integer tenantId;
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    @TableField("del_flag")
    private Integer delFlag;
}
