package org.jeecg.modules.device.dto;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.time.LocalDateTime;

/**
 * 设备授权详情 DTO
 */
@Data
public class DeviceAuthDetailDTO {

    private String id;

    /** 租户ID/名称（冗余） */
    private String tenantId;
    private String tenantName;

    /** 企业ID/名称（冗余） */
    private String enterpriseId;
    private String enterpriseName;

    /** 主控设备 */
    private String controllerId;
    private String controllerCode;
    private String controllerName;

    /** 机器人设备 */
    private String deviceId;
    private String deviceCode;
    private String deviceName;

    /** 管理后台账号信息 */
    private String adminUserId;
    private String adminUsername;

    /** 生效/失效 */
    private LocalDateTime effectiveTime;
    private LocalDateTime expireTime;

    /** 授权状态：1 启用 0 禁用 */
    @Dict(dicCode = "device_auth_status")
    private Integer status;

    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;
}

