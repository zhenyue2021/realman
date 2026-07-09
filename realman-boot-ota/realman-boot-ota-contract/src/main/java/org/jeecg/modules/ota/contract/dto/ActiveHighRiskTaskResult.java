package org.jeecg.modules.ota.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 对应 {@code GET /internal/ota/devices/{deviceId}/active-high-risk-task}。
 * 供设备管理业务平台在取消测试设备标记前做前置校验回调，见 OTA 平台详细设计
 * 第七章、设备基座详细设计 3.5 测试设备取消标记时序图。
 */
@Data
@Schema(description = "设备是否存在进行中的高风险升级任务")
public class ActiveHighRiskTaskResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否存在进行中的 high_risk 任务")
    private boolean hasActiveTask;

    @Schema(description = "存在时返回任务 ID，便于运维人员定位")
    private String taskId;
}
