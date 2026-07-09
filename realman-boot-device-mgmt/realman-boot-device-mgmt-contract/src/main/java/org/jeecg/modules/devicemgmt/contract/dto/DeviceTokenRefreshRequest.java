package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * Device Token 续签请求。调用方：设备通信中台，触发时机是设备上行
 * {@code device/{code}/ota/token-refresh}（见设备通信中台详细设计、
 * OTA 平台详细设计第二章协议映射表 {@code POST /api/v1/devices/token/refresh}）。
 */
@Data
@Schema(description = "Device Token 续签请求")
public class DeviceTokenRefreshRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "设备上行携带的旧 Token（JWT）")
    private String oldToken;
}
