package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * MQTT 连接层密钥校验请求。对应 ADR-0001 规划的
 * {@code POST /internal/device/validate-secret}，由设备通信中台在 EMQX
 * {@code MqttAuthController} 回调时同步调用。
 */
@Data
@Schema(description = "设备连接密钥校验请求")
public class DeviceSecretValidationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "MQTT clientId，即设备码")
    private String deviceCode;

    @NotBlank
    @Schema(description = "MQTT CONNECT 携带的密码")
    private String deviceSecret;
}
