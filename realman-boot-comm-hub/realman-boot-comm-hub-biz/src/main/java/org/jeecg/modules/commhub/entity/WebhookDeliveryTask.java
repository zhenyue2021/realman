package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("webhook_delivery_task")
public class WebhookDeliveryTask implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("event_log_id")
    private String eventLogId;
    @TableField("subscription_id")
    private String subscriptionId;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("callback_url")
    private String callbackUrl;
    @TableField("hmac_secret")
    private String hmacSecret;
    @TableField("request_body")
    private String requestBody;
    @TableField("status")
    private String status;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("max_attempts")
    private Integer maxAttempts;
    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;
    @TableField("last_error")
    private String lastError;
    @TableField("last_status_code")
    private Integer lastStatusCode;
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
