package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Device Token 续签结果，供设备通信中台下行回传给设备。 */
@Data
@Schema(description = "Device Token 续签结果")
public class DeviceTokenRefreshResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "新的业务身份 Token（JWT）")
    private String deviceToken;

    @Schema(description = "新 Token 过期时间")
    private LocalDateTime tokenExpiresAt;
}
