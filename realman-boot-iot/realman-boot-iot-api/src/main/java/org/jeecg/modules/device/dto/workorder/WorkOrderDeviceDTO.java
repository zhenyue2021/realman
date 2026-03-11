package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderDeviceDTO {

    private String deviceType;

    private String deviceId;

    private String deviceName;

    private String deviceCode;

    private String actualDeviceId;

    private String actualDeviceName;

    private String actualDeviceCode;
}

