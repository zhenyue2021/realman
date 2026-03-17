package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderComplianceQueryDTO {

    private Integer pageNo;

    private Integer pageSize;

    private String agentId;

    private String enterpriseId;

    /**
     * 应用状态：0-未应用 1-已应用
     */
    private Integer applyStatus;
}

