package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 PUT /api/v1/ota-keys/{id}/revoke（PRD 9.3.4），须输入确认文本 REVOKE。 */
@Data
@Schema(description = "紧急吊销公钥请求")
public class KeyRevokeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String confirmText;

    private String reason;
}
