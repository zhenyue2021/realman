package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;

@Data
@Schema(description = "业务身份 Token 校验结果")
public class DeviceTokenValidationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否有效（未过期、未吊销）")
    private boolean valid;

    @Schema(description = "无效原因，如 ERR_TOKEN_REVOKED / ERR_TOKEN_EXPIRED")
    private String reason;

    @Schema(description = "校验通过时返回的设备身份信息")
    private String deviceId;

    private String tenantId;

    private DeviceType deviceType;
}
