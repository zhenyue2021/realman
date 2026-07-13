package org.jeecg.modules.device.datacollect.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("darwin_http_outbox")
public class DarwinHttpOutbox implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("path")
    private String path;
    @TableField("request_body")
    private String requestBody;
    @TableField("request_hash")
    private String requestHash;
    @TableField("biz_key")
    private String bizKey;
    @TableField("device_code")
    private String deviceCode;
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
    @TableField("locked_by")
    private String lockedBy;
    @TableField("locked_at")
    private LocalDateTime lockedAt;
    @TableField("lock_expire_at")
    private LocalDateTime lockExpireAt;
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
