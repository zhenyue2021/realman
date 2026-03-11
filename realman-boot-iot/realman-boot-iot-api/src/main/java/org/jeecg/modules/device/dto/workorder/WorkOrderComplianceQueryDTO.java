package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderComplianceQueryDTO {

    private Integer pageNo;

    private Integer pageSize;

    private String agentId;

    private Integer status;
}

