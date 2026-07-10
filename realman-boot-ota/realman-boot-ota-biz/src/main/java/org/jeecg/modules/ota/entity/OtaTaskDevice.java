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
 * 设备级升级子任务，15 态状态机的真正载体。对齐 OTA 平台详细设计八章（PRD 第五章）。
 */
@Data
@TableName("ota_task_device")
public class OtaTaskDevice implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("task_id")
    private String taskId;

    @TableField("device_id")
    private String deviceId;

    @TableField("device_code")
    private String deviceCode;

    /** 15 态之一，见 OtaTaskState */
    @TableField("state")
    private String state;

    @TableField("progress_pct")
    private Integer progressPct;

    /** install_exec / symlink_switch / os_sync，仅 EXECUTING 阶段非空 */
    @TableField("sub_stage")
    private String subStage;

    /** pass / fail / skipped，CHECKING 阶段完成后非空 */
    @TableField("sig_verify_result")
    private String sigVerifyResult;

    @TableField("upgrade_error_code")
    private String upgradeErrorCode;

    @TableField("upgrade_error_msg")
    private String upgradeErrorMsg;

    @TableField("rollback_reason")
    private String rollbackReason;

    @TableField("reported_at")
    private LocalDateTime reportedAt;

    @TableField("state_changed_at")
    private LocalDateTime stateChangedAt;

    @TableField("retry_count")
    private Integer retryCount;

    /** EXECUTING 阶段发起取消请求的时间，非空表示正等待设备端 symlink_switched 上报；
     * 供 cancel_ack_timeout 兜底扫描判断是否超时（见 OTA 平台详细设计 4.6.1）。 */
    @TableField("cancel_requested_at")
    private LocalDateTime cancelRequestedAt;

    /** 下行发布尝试次数，区别于运维手动重试的 {@link #retryCount}；达到 dispatch_max_attempts 后置 FAILED。 */
    @TableField("dispatch_attempt_count")
    private Integer dispatchAttemptCount;

    /** 最近一次下行发布尝试时间，供自动重试扫描判断退避间隔是否已到。 */
    @TableField("last_dispatch_attempt_at")
    private LocalDateTime lastDispatchAttemptAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
