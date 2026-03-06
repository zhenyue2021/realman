package org.jeecg.modules.device.dto;

import lombok.Data;

@Data
public class DeviceRestartDTO {
    private String reason;
    private String operator;
}
