package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Webhook 投递任务。事件入库后先生成任务，由定时 worker 扫描并执行单次 HTTP 推送，
 * 重试退避通过更新 next_retry_at 实现，避免异步线程长时间 sleep。
 */
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

    @TableField("callback_url")
    private String callbackUrl;

    /** PENDING / SENDING / RETRYING / SUCCESS / FAILED */
    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    @TableField("last_error")
    private String lastError;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
