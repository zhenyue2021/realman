package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

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
     * 1-机器人设备 2-主控设备
     */
    @Dict(dicCode = "device_type")
    @TableField("device_type")
    private Integer deviceType;
    @TableField("product_id")
    private String productId;
    @TableField("device_model")
    private String deviceModel;
    @TableField("serial_number")
    private String serialNumber;
    /** 设备网卡MAC地址 */
    @TableField("mac_address")
    private String macAddress;
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
    /** 主控设备最后一次登录时间（操作员登录主控端时更新） */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;
    @TableField("longitude")
    private BigDecimal longitude;
    @TableField("latitude")
    private BigDecimal latitude;
    /** MQTT 最近一次连接鉴权后解析的行政区划文案（高德 IP+逆地理），内网为「内网IP」；未配置 Key 时为纯 IP */
    @TableField("address")
    private String address;
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
