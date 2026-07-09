package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 POST /api/v1/ota-keys（PRD 9.3.1）。 */
@Data
@Schema(description = "上传 OTA 公钥请求")
public class KeyUploadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "Ed25519 公钥，PEM 格式（标准 X.509 SubjectPublicKeyInfo DER 编码）")
    private String publicKeyPem;

    private String keyAlias;
}
