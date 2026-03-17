package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单合规配置详情 DTO
 */
@Data
public class WorkOrderComplianceConfigDetailDTO implements Serializable {

    private String id;

    private String agentId;
    private String agentName;

    private String enterpriseId;
    private String enterpriseName;

    private String taskScene;

    private Integer timeoutAlertEnabled;
    private String timeoutAlertOffset;

    private Integer taskLimitEnabled;

    private Integer acceptanceEnabled;

    private String overtimeReasonEnum;
    private String overtimeReasonDesc;

    private Integer autoCloseEnabled;
    private String autoCloseOffset;

    private Integer applyStatus;

    private String createBy;
    private LocalDateTime createTime;

    private String updateBy;
    private LocalDateTime updateTime;

    private Integer delFlag;
    private String tenantId;
}

