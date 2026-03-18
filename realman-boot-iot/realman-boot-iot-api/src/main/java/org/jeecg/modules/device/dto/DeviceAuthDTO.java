package org.jeecg.modules.device.dto;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.time.LocalDateTime;

/**
 * 设备授权 DTO（用于分页、创建、编辑返回）
 */
@Data
public class DeviceAuthDTO {

    private String id;

    /** 租户ID */
    private String tenantId;

    /** 租户名称 */
    private String tenantName;

    /** 企业ID */
    private String enterpriseId;

    /** 企业名称 */
    private String enterpriseName;

    /** 主控端ID */
    private String controllerId;

    /** 主控端设备编码（冗余） */
    private String controllerCode;

    /** 设备ID */
    private String deviceId;

    /** 设备编码（机器人编码冗余） */
    private String deviceCode;

    /** 管理后台账号ID */
    private String adminUserId;

    /** 管理后台账号用户名 */
    private String adminUsername;

    /** 生效时间 */
    private LocalDateTime effectiveTime;

    /** 失效时间 */
    private LocalDateTime expireTime;

    /** 授权状态：1 启用 0 禁用 */
    @Dict(dicCode = "device_auth_status")
    private Integer status;

    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;
}

