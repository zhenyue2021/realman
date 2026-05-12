package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderStartDTO {

    private String operatorId;

    private String operatorName;

    private String operatorPhone;

    /** Darwin 工单开启时绑定的主控设备编码（非 Darwin 工单传 null） */
    private String controllerCode;

    /** Darwin 工单开启时绑定的机器人设备编码（非 Darwin 工单传 null） */
    private String robotCode;
}

