package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "Webhook 订阅")
public class WebhookSubscriptionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String tenantId;

    private String callbackUrl;

    private List<String> eventKinds;

    /** ACTIVE / DISABLED */
    private String status;

    private String createdBy;

    private LocalDateTime createdAt;
}
