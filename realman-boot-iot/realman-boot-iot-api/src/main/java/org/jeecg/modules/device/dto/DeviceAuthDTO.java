package org.jeecg.modules.device.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备授权 DTO（用于分页、创建、编辑返回）
 */
@Data
public class DeviceAuthDTO {

    private String id;

    /** 授权主体类型：USER/TENANT/... */
    private String subjectType;

    /** 授权主体ID */
    private String subjectId;

    /** 主控端ID */
    private String controllerId;

    /** 设备ID */
    private String deviceId;

    /** 设备编码 */
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
    private Integer status;

    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;
}

