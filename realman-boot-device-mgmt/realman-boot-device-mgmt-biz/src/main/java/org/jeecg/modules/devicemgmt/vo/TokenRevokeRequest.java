package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 PUT /api/v1/devices/{deviceId}/token/revoke，需 confirmText=REVOKE_TOKEN 二次确认。 */
@Data
@Schema(description = "Token 吊销请求")
public class TokenRevokeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String confirmText;

    private String reason;
}
