package org.jeecg.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设备 HTTP 自注册请求体。
 *
 * <p>签名规则：{@code sign = Md5Util.md5Encode(deviceCode + "|" + macAddress + "|" + timestamp, "UTF_8").toUpperCase()}
 * <p>deviceCode、macAddress 均以设备上传为准（仅 trim），签名与入库使用相同字符串。
 */
@Data
public class DeviceProvisionRequestDTO {

    @NotBlank(message = "deviceCode 不能为空")
    private String deviceCode;

    @NotBlank(message = "macAddress 不能为空")
    private String macAddress;

    @NotBlank(message = "deviceModel 不能为空")
    private String deviceModel;

    /** 设备名称；未传时默认使用 deviceCode */
    private String deviceName;

    /** Unix 毫秒时间戳 */
    @NotNull(message = "timestamp 不能为空")
    private Long timestamp;

    @NotBlank(message = "sign 不能为空")
    private String sign;

    @NotBlank(message = "设备类型不能为空")
    private String deviceType;
    private String description;
}
