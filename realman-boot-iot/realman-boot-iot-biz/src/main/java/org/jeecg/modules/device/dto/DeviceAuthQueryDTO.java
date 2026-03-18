package org.jeecg.modules.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备授权管理 - 列表查询条件
 */
@Data
public class DeviceAuthQueryDTO {
    /** 租户ID */
    private String tenantId;

    /** 企业ID */
    private String enterpriseId;

    /** 主控端ID */
    private String controllerId;

    /** 主控端编码 */
    private String controllerCode;

    /** 机器人/设备ID */
    private String deviceId;

    /** 机器人编码 */
    private String deviceCode;

    /** 生效时间范围 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startEffectiveTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endEffectiveTime;

    /** 授权状态：1 启用 0 禁用 */
    private Integer status;

    /** 分页 */
    private Integer pageNo;
    private Integer pageSize;
}

