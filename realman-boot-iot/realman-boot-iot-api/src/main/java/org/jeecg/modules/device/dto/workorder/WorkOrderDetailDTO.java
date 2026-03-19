package org.jeecg.modules.device.dto.workorder;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.util.List;

@Data
public class WorkOrderDetailDTO {

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

    @Dict(dicCode = "order_status")
    private String status;

    @Dict(dicCode = "audit_result")
    private String auditResult;

    private String operatorId;

    private String operatorName;

    private String operatorPhone;

    private String actualStartTime;

    private String submitTime;

    private String timeoutReason;

    private String timeoutReasonSource;

    private String auditBy;

    private String auditTime;

    private String auditComment;

    private String closeBy;

    private String closeTime;

    private String closeReason;

    private String createBy;

    private String createTime;

    private String updateBy;

    private String updateTime;

    private String tenantId;

    private List<WorkOrderDeviceDTO> devices;
}

