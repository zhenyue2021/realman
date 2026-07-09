package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 业务身份 Token 校验请求。用于心跳/进度上报等业务语义的身份校验，见设备基座
 * 详细设计 3.3 双凭证体系。既服务于设备端向 MQTT 报文里携带的 {@code device_token}
 * 字段，也服务于 WEB 端向 HTTP-MQTT 桥接、北向业务 API 的 Bearer Token 鉴权。
 */
@Data
@Schema(description = "业务身份 Token 校验请求")
public class DeviceTokenValidationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "Bearer JWT")
    private String deviceToken;
}
