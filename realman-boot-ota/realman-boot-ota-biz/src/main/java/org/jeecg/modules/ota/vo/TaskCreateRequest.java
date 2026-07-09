package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 POST /api/v1/ota/tasks（PRD 9.5.1）。 */
@Data
@Schema(description = "创建升级任务请求")
public class TaskCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "master / slave，后端据此内部映射升级目标角色，不接受/不暴露 target 字段")
    private String deviceType;

    @NotBlank
    private String packageId;

    @NotBlank
    @Schema(description = "BY_SN / BY_MODEL / ALL / BY_TENANT_MODEL")
    private String upgradeMode;

    @Schema(description = "upgradeMode=BY_SN 时必填")
    private String sn;

    @Schema(description = "upgradeMode=BY_MODEL/BY_TENANT_MODEL 时必填")
    private String model;

    @Schema(description = "upgradeMode=BY_TENANT_MODEL 时必填")
    private String tenantId;

    private Double bandwidthLimitMbps;

    @Schema(description = "count / percent，为空使用系统全局默认值")
    private String failThresholdType;

    private Integer failThreshold;

    @Schema(description = "pause / stop_all / continue，为空使用系统全局默认值")
    private String onThresholdExceeded;
}
