package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderAuditDTO {

    /** 0-合格，1-不合格 */
    private String result;

    private String comment;
}

