package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 POST /api/v1/admin/devices/registration-secret，超管生成一次性注册凭证。 */
@Data
@Schema(description = "生成一次性注册凭证请求")
public class RegistrationSecretGenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String deviceCode;

    @NotBlank
    private String tenantId;
}
