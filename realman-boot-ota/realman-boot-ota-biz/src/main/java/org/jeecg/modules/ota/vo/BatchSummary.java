package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 批量任务汇总，对齐 PRD 9.5.3 batch_summary 字段。 */
@Data
@Schema(description = "批量任务汇总")
public class BatchSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;

    private long completed;

    private long failed;

    private long cancelled;

    private long inProgress;

    private int completionRatePct;

    /** total（正常执行中）/ executed（abort 后切换为 total-cancelled） */
    private String completionRateBasis;

    private boolean stopAllTriggered;

    private LocalDateTime thresholdTriggeredAt;

    private String onThresholdExceeded;

    private LocalDateTime pausedAt;

    private int resumeCount;
}
