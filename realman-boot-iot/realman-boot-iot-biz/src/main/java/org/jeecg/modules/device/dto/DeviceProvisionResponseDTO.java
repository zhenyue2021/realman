package org.jeecg.modules.device.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 设备 HTTP 自注册响应。
 */
@Data
@Builder
public class DeviceProvisionResponseDTO {

    /** 业务码：0=新注册 1=幂等返回；40001+ 为业务失败，见 DeviceProvisionBizCode */
    private int bizCode;

    /** 业务描述，供日志/调试；设备端应以 bizCode 为准 */
    private String bizMessage;

    /** 平台分配的设备编码（MQTT clientId / username） */
    private String deviceCode;

    /** MQTT 连接密码（MD5(deviceCode) 小写 Hex，与平台 generateSecret 一致） */
    private String mqttPassword;

    /** MQTT Broker 地址，如 tcp://host:1883 */
    private String mqttBrokerUrl;

    /** true=本次新注册；false=设备已存在（幂等返回） */
    private boolean newlyRegistered;

    /** 设备状态：0-未激活 1-在线 2-离线 3-禁用 */
    private Integer status;
}
