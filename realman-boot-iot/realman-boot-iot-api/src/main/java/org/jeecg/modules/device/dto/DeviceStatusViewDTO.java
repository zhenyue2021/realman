package org.jeecg.modules.device.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备状态上报记录视图（详情聚合用）
 */
@Data
public class DeviceStatusViewDTO {

    private String id;
    private String deviceId;
    private String deviceCode;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private BigDecimal batteryLevel;
    private Integer signalStrength;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer runStatus;
    private String rawData;
    private LocalDateTime reportTime;
    private LocalDateTime receiveTime;
}
