package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "一次性注册凭证状态")
public class RegistrationSecretStatusResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceCode;

    /** UNUSED / USED / EXPIRED / NOT_FOUND */
    private String status;

    private LocalDateTime expiresAt;

    private LocalDateTime usedAt;
}
