package org.jeecg.modules.device.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @TableName("iot_ota_upgrade_record")
public class IotOtaUpgradeRecord implements Serializable {
    @TableId(value="id",type=IdType.ASSIGN_ID) private String id;
    @TableField("task_id")           private String taskId;
    @TableField("device_id")         private String deviceId;
    @TableField("device_code")       private String deviceCode;
    @TableField("firmware_id")       private String firmwareId;
    @TableField("old_version")       private String oldVersion;
    @TableField("target_version")    private String targetVersion;
    @TableField("upgrade_status")    private Integer upgradeStatus;
    @TableField("download_progress") private Integer downloadProgress;
    @TableField("downloaded_bytes")  private Long downloadedBytes;
    @TableField("fail_reason")       private String failReason;
    @TableField("notify_time")       private LocalDateTime notifyTime;
    @TableField("start_time")        private LocalDateTime startTime;
    @TableField("finish_time")       private LocalDateTime finishTime;
    @TableField("retry_count")       private Integer retryCount;
    @TableField(value="create_time",fill=FieldFill.INSERT) private LocalDateTime createTime;
    @TableField(value="update_time",fill=FieldFill.INSERT_UPDATE) private LocalDateTime updateTime;
}
