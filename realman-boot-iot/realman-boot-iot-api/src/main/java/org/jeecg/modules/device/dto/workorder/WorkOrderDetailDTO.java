package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

import java.util.List;

@Data
public class WorkOrderDetailDTO {

    private String agentId;

    private String agentName;

    private String departmentId;

    private String departmentName;

    private String complianceId;

    private String remark;

    private String planStartTime;

    private String planEndTime;

    private String status;

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

