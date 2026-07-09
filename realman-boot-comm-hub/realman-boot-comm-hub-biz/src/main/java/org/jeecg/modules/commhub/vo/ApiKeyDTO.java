package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "API Key 台账")
public class ApiKeyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String tenantId;

    @Schema(description = "原始 Key 前 8 位，仅供辨识")
    private String keyPrefix;

    private List<String> deviceScope;

    private List<String> topicSuffixScope;

    /** ACTIVE / REVOKED */
    private String status;

    private String createdBy;

    private LocalDateTime createdAt;
}
