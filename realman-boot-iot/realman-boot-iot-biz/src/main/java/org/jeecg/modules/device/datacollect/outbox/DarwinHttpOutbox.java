package org.jeecg.modules.device.datacollect.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("darwin_http_outbox")
public class DarwinHttpOutbox implements Serializable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    @TableField("path")
    private String path;
    @TableField("request_body")
    private String requestBody;
    @TableField("device_code")
    private String deviceCode;
    @TableField("status")
    private String status;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("next_retry_at")
    private Date nextRetryAt;
    @TableField("last_error")
    private String lastError;
    @TableField("created_at")
    private Date createdAt;
}
