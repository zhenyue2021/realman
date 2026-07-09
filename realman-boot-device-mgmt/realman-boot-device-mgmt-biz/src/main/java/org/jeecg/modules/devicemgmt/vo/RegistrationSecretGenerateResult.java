package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "一次性注册凭证生成结果（明文凭证仅此一次返回）")
public class RegistrationSecretGenerateResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String secretId;

    private String deviceRegistrationSecret;

    private LocalDateTime expiresAt;
}
