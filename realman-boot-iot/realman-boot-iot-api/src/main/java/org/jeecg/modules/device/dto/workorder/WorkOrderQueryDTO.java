package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderQueryDTO {

    private Integer pageNo;

    private Integer pageSize;

    private String agentId;

    private String status;
}

