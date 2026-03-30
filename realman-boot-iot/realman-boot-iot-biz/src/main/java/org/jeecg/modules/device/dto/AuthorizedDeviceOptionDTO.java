package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 已授权给企业的设备选项 DTO（用于工单创建时选择设备）
 */
@Data
public class AuthorizedDeviceOptionDTO {

    /** iot_device.id */
    private String id;

    /** 设备编码 */
    private String deviceCode;

    /** 设备名称 */
    private String deviceName;

    /**
     * 在线状态：0-未激活 1-在线 2-离线 3-禁用
     */
    private Integer status;

    /**
     * 使用状态：0-空闲 1-占用
     */
    private Integer useStatus;

    /** 设备型号 */
    private String deviceModel;
}
