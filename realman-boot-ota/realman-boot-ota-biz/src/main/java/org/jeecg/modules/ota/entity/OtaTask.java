package org.jeecg.modules.ota.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 升级任务（批量视图）。对齐 OTA 平台详细设计五章（PRD 4.4.1）。单设备任务
 * 是"批次大小为 1"的特例，不单独建模。{@code status} 是聚合视图，真正的
 * 15 态状态机在 {@link OtaTaskDevice} 上逐设备维护。
 */
@Data
@TableName("ota_task")
public class OtaTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "task_id", type = IdType.ASSIGN_ID)
    private String taskId;

    /** master / slave */
    @TableField("device_type")
    private String deviceType;

    @TableField("package_id")
    private String packageId;

    /** BY_SN / BY_MODEL / ALL / BY_TENANT_MODEL */
    @TableField("upgrade_mode")
    private String upgradeMode;

    /** JSON 字符串：{sn} / {model} / {tenantId, model} 视 upgrade_mode 而定 */
    @TableField("target_selector")
    private String targetSelector;

    /** 仅 BY_TENANT_MODEL 时非空 */
    @TableField("tenant_id")
    private String tenantId;

    @TableField("bandwidth_limit_mbps")
    private Double bandwidthLimitMbps;

    /** count / percent */
    @TableField("fail_threshold_type")
    private String failThresholdType;

    @TableField("fail_threshold")
    private Integer failThreshold;

    /** pause / stop_all / continue */
    @TableField("on_threshold_exceeded")
    private String onThresholdExceeded;

    /** 聚合状态：IN_PROGRESS / PAUSED / COMPLETED / PARTIAL_COMPLETED / FAILED / CANCELLED */
    @TableField("status")
    private String status;

    @TableField("stop_all_triggered")
    private Boolean stopAllTriggered;

    @TableField("active_fail_threshold_snapshot")
    private Integer activeFailThresholdSnapshot;

    @TableField("threshold_triggered_at")
    private LocalDateTime thresholdTriggeredAt;

    @TableField("paused_at")
    private LocalDateTime pausedAt;

    @TableField("resume_count")
    private Integer resumeCount;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
