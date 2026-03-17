package org.jeecg.modules.device.dto.workorder;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

@Data
public class WorkOrderDeviceDTO {

    private String deviceType;

    private String deviceId;

    private String deviceName;

    private String deviceCode;

    private String actualDeviceId;

    private String actualDeviceName;

    private String actualDeviceCode;

    /**
     * 设备当前状态（0-未激活 1-在线 2-离线 3-禁用）
     */
    @Dict(dicCode = "device_status")
    private Integer status;

    /**
     * 设备型号
     */
    private String deviceModel;

    /**
     * 固件版本
     */
    private String firmwareVersion;

    /**
     * 最新经度
     */
    private String longitude;

    /**
     * 最新纬度
     */
    private String latitude;
}

