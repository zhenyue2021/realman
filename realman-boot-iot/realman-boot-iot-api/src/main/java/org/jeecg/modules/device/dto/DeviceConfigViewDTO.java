package org.jeecg.modules.device.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备参数配置视图（详情聚合用，非持久化实体直出）
 */
@Data
public class DeviceConfigViewDTO {

    private String id;
    private String deviceId;
    private String deviceCode;
    private String configKey;
    private String configValue;
    private String configType;
    private Integer syncStatus;
    private LocalDateTime syncTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
