package org.jeecg.modules.device.dto.workorder;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class WorkOrderComplianceCreateDTO {


    @NotEmpty(message = "代理商ID不能为空")
    private String agentId;
    @NotEmpty(message = "代理商名称不能为空")
    private String agentName;
    @NotEmpty(message = "企业ID不能为空")
    private String enterpriseId;
    @NotEmpty(message = "企业名称不能为空")
    private String enterpriseName;

    private String taskScene;

    private Integer timeoutAlertEnabled;

    private String timeoutAlertOffset;

    private Integer taskLimitEnabled;

    private Integer acceptanceEnabled;

    /** 是否启用超时提交：0-禁用 1-启用 */
    private Integer overtimeEnabled;

    private String overtimeReasonEnum;

    private String overtimeReasonDesc;

    private Integer autoCloseEnabled;

    private String autoCloseOffset;



    private String tenantId;
}

