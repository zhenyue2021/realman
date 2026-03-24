package org.jeecg.modules.device.dto;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 机器人设备分页列表 / 简要详情响应 DTO（不包含密钥等敏感字段）
 */
@Data
public class RobotDevicePageItemDTO {

    private String id;
    private String deviceCode;
    private String deviceName;
    @Dict(dicCode = "device_type")
    private Integer deviceType;
    private String productId;
    private String deviceModel;
    private String serialNumber;
    private String macAddress;
    private String firmwareVersion;
    @Dict(dicCode = "device_status")
    private Integer status;
    private String description;
    private String address;
    private LocalDateTime lastOnlineTime;
    private LocalDateTime lastOfflineTime;
    private LocalDateTime lastLoginTime;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String createBy;
    private Integer tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 设备当前运行状态（与 iot_device.status 语义一致，便于前端列表展示）
     */
    private Integer runningStatus;


    /**
     * 授权生效/失效时间（来自 iot_device_auth，按当前租户筛选）
     */
    private LocalDateTime authEffectiveTime;
    private LocalDateTime authExpireTime;
}
