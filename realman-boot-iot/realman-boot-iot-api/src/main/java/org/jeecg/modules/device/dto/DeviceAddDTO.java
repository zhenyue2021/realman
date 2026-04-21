package org.jeecg.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceAddDTO {
    @NotBlank(message = "设备编号不能为空")
    private String deviceCode;
    @NotBlank(message = "设备名称不能为空")
    private String deviceName;
    private String deviceType;
    private String productId;
    private String deviceModel;
    private String serialNumber;
    /** 设备网卡MAC地址 */
    private String macAddress;
    private String description;
}
