package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "创建 Webhook 订阅请求")
public class WebhookSubscriptionCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String callbackUrl;

    @Schema(description = "订阅的事件种类，缺省表示订阅全部")
    private List<String> eventKinds;
}
