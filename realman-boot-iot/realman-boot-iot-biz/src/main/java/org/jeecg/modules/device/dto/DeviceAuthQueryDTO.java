package org.jeecg.modules.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备授权管理 - 列表查询条件
 */
@Data
public class DeviceAuthQueryDTO {
    /** 授权主体类型：USER / TENANT 等 */
    private String subjectType;

    /** 授权主体ID（如 username 或 tenantId） */
    private String subjectId;

    /** 主控端ID */
    private String controllerId;

    /** 机器人/设备ID */
    private String deviceId;

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

