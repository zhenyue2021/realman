package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;

/**
 * 设备上电自注册转发请求。
 *
 * <p>对应设备通信中台南向唯一的 HTTP 例外（{@code POST /internal/device/provision}）：
 * 通信中台收到设备的自注册请求后原样转发到这里，本服务负责真正的业务校验
 * （{@code device_registration_secret} 是否有效、{@code deviceCode} 是否已在
 * 达尔文设备管理平台授权给该租户等），通信中台自身不做业务校验，见设备通信中台
 * 详细设计 3.1。
 */
@Data
@Schema(description = "设备上电自注册请求（由通信中台转发）")
public class DeviceProvisionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "设备序列号 / 通信层标识")
    private String deviceCode;

    @NotNull
    @Schema(description = "设备类型")
    private DeviceType deviceType;

    @NotBlank
    @Schema(description = "所属租户")
    private String tenantId;

    @NotBlank
    @Schema(description = "出厂预置的一次性注册凭证，使用后立即作废")
    private String deviceRegistrationSecret;

    @Schema(description = "网络硬件地址")
    private String macAddress;

    @Schema(description = "型号")
    private String deviceModel;
}
