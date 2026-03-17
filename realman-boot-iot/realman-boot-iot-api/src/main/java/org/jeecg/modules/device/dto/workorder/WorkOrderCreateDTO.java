package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

import java.util.List;

@Data
public class WorkOrderCreateDTO {

    /**
     * 工单任务名称
     */
    private String taskName;

    /**
     * 币种（如 CNY / USD）
     */
    private String currency;

    /**
     * 单价
     */
    private String unitPrice;

    /**
     * 总价
     */
    private String totalPrice;

    private String agentId;

    private String agentName;

    private String departmentId;

    private String departmentName;

    private String complianceId;

    private String remark;

    private String planStartTime;

    private String planEndTime;

    private String tenantId;

    private List<WorkOrderDeviceDTO> devices;
}

