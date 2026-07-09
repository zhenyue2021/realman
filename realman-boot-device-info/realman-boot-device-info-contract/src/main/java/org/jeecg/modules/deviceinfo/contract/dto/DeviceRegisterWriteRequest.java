package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;

/**
 * 注册写入请求。对应 {@code POST /internal/device-info/register}。
 *
 * <p>由设备管理业务平台在注册成功后调用，写入静态字段并把
 * {@code lifecycleStage} 置为 {@code ACTIVATED}（见设备基座详细设计 2.2）。
 */
@Data
@Schema(description = "设备注册写入请求（写入 SSOT 静态字段）")
public class DeviceRegisterWriteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "内部唯一标识（UUID），由设备管理业务平台在注册流程中生成")
    private String deviceId;

    @NotBlank
    @Schema(description = "设备序列号 / 通信层标识")
    private String deviceCode;

    @NotNull
    @Schema(description = "设备类型")
    private DeviceType deviceType;

    @NotBlank
    @Schema(description = "所属租户")
    private String tenantId;

    @Schema(description = "型号")
    private String deviceModel;

    @Schema(description = "展示名称")
    private String deviceName;

    @Schema(description = "网络硬件地址")
    private String macAddress;
}
