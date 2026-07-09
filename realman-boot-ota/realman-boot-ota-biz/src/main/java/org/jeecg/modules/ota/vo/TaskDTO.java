package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** 对应 PRD 9.5.3 升级任务详情响应。 */
@Data
@Schema(description = "升级任务详情")
public class TaskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    private String deviceType;

    private String packageId;

    private String upgradeMode;

    /** 聚合状态：IN_PROGRESS / PAUSED / COMPLETED / PARTIAL_COMPLETED / FAILED / CANCELLED */
    private String status;

    private Integer activeFailThresholdSnapshot;

    private BatchSummary batchSummary;

    private List<TaskDeviceDTO> subTasks;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
