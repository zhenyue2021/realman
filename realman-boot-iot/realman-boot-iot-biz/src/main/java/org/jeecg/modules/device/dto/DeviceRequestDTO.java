package org.jeecg.modules.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceRequestDTO {
    private Integer pageNo;
    private Integer pageSize;
    private String deviceName;
    private Integer deviceType;
    private Integer status;
    private String productId;

    /**
     * 条件查询（按时间）：创建时间开始/结束
     * 前端传：yyyy-MM-dd HH:mm:ss
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /**
     * 内部使用：当前登录用户名 / 是否超级管理员，用于数据权限控制
     * 这两个字段无需前端传入，由控制层根据登录态填充
     */
    private String currentUsername;
    private Boolean superAdmin;

    /** 内部使用：当前登录租户ID（若有多租户头，可在控制器中填充） */
    private String currentTenantId;
}
