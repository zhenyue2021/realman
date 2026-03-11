package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderTimeoutReasonDTO {

    private String reason;

    /**
     * 原因来源：OPERATOR / ADMIN
     */
    private String source;
}

