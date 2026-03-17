package org.jeecg.modules.device.dto;

import lombok.Data;
import org.jeecg.modules.device.entity.IotDevice;

import java.time.LocalDateTime;

/**
 * 主控设备分页列表展示 DTO
 */
@Data
public class MasterDevicePageItemDTO {

    /**
     * 主控设备基础信息
     */
    private IotDevice device;

    /**
     * 设备当前运行状态（复用 iot_device.status 语义）
     */
    private Integer runningStatus;

    /**
     * 最近登录操作员信息（来自 iot_controller_login_log）
     */
    private String lastLoginOperatorId;
    private String lastLoginOperatorName;
    private LocalDateTime lastLoginTime;

    /**
     * 授权生效/失效时间（来自 iot_device_auth，按当前租户筛选）
     */
    private LocalDateTime authEffectiveTime;
    private LocalDateTime authExpireTime;

    /**
     * 由经纬度解析出的地址（尽力而为，失败返回 null）
     */
    private String address;
}

