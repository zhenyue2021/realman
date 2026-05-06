package org.jeecg.modules.device.vo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class IotDeviceDetail implements Serializable {
    private String id;
    private String deviceCode;
    private String deviceName;
    /**
     * 1-机器人设备 2-主控设备
     */
    @Dict(dicCode = "device_type")
    private Integer deviceType;
    private String productId;
    private String deviceModel;
    private String serialNumber;
    /** 设备网卡MAC地址 */
    private String macAddress;
    private String firmwareVersion;
    /**
     * 0-未激活 1-在线 2-离线 3-禁用
     */
    @Dict(dicCode = "device_status")
    private Integer status;
    /**
     * 使用状态：0-空闲 1-占用（使用中）
     */
    @Dict(dicCode = "device_use_status")
    private Integer useStatus;
    /**
     * 64位Hex设备密钥，MQTT连接密码，同时派生AES Key
     */
    private LocalDateTime secretCreateTime;
    private String description;
    private LocalDateTime lastOnlineTime;
    private LocalDateTime lastOfflineTime;
    /** 主控设备最后一次登录时间（操作员登录主控端时更新） */
    private LocalDateTime lastLoginTime;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String address;
    private String createBy;
    private Integer tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer delFlag;
    // 授权信息
    private LocalDateTime authEffectiveTime;
    private LocalDateTime authExpireTime;
    private String authEnterpriseName;
    /**
     * 设备当前运行SLAM 地图版本号
     */
    private String slamVersion;

    /**
     * 设备当前运行SLAM 地图 预签名URL
     */
    private String slamPresignedUrl;

}
