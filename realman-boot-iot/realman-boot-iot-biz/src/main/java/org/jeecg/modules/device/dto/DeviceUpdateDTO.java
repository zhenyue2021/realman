package org.jeecg.modules.device.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 设备编辑 DTO（仅允许更新以下字段，deviceCode/deviceType 不可改）
 */
@Data
public class DeviceUpdateDTO {
    private String deviceName;
    private String deviceModel;
    private String serialNumber;
    private String description;
    private String macAddress;
    private BigDecimal longitude;
    private BigDecimal latitude;
}
