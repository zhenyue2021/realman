package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "设备连接密钥校验结果")
public class DeviceSecretValidationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否允许连接")
    private boolean allow;

    @Schema(description = "拒绝原因，allow=false 时返回，如 ERR_DEVICE_NOT_AUTHORIZED / ERR_TOKEN_REVOKED")
    private String reason;

    @Schema(description = "校验通过时返回内部设备 ID，便于通信中台后续埋点使用")
    private String deviceId;
}
