package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 不含完整公钥内容，防止意外泄露（PRD 9.3.2）。 */
@Data
@Schema(description = "OTA 公钥（列表/详情视图）")
public class KeyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String keyId;

    private String keyFingerprint;

    private String keyAlias;

    /** active / pending_activation / revoked */
    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime activatedAt;

    private LocalDateTime revokedAt;
}
