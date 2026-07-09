package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "Webhook 订阅创建结果（HMAC 密钥仅此一次以明文返回）")
public class WebhookSubscriptionCreateResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String hmacSecret;
}
