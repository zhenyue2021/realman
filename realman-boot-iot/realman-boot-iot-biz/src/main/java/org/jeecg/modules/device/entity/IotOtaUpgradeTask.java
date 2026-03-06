package org.jeecg.modules.device.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @TableName("iot_ota_upgrade_task")
public class IotOtaUpgradeTask implements Serializable {
    @TableId(value="id",type=IdType.ASSIGN_ID) private String id;
    @TableField("task_name")       private String taskName;
    @TableField("firmware_id")     private String firmwareId;
    @TableField("firmware_version") private String firmwareVersion;
    @TableField("task_status")     private Integer taskStatus;
    @TableField("upgrade_type")    private Integer upgradeType;
    @TableField("total_count")     private Integer totalCount;
    @TableField("success_count")   private Integer successCount;
    @TableField("fail_count")      private Integer failCount;
    @TableField("upgrading_count") private Integer upgradingCount;
    @TableField("actual_start_time") private LocalDateTime actualStartTime;
    @TableField("finish_time")     private LocalDateTime finishTime;
    @TableField("create_by")       private String createBy;
    @TableField(value="create_time",fill=FieldFill.INSERT) private LocalDateTime createTime;
    @TableField(value="update_time",fill=FieldFill.INSERT_UPDATE) private LocalDateTime updateTime;
}
