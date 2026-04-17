package org.jeecg.modules.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jeecg.common.aspect.annotation.Dict;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper=false)
public class WorkOrderDTO extends WorkOrder {

    private String id;

    private String taskName;

    private String agentId;

    private String agentName;

    private String departmentId;

    private String departmentName;

    private String complianceId;

    private String currency;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    @Dict(dicCode = "order_status")
    private String status;

    @Dict(dicCode = "audit_result")
    private String auditResult;

    private String operatorId;

    private String operatorName;

    private String operatorPhone;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submitTime;

    private String timeoutReason;

    private String timeoutReasonSource;

    private String auditBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime auditTime;

    private String auditComment;

    private String closeBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime closeTime;

    private String closeReason;

    private String createBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String updateBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    private Integer delFlag;

    private String tenantId;

    private WorkOrderComplianceConfig workOrderComplianceConfig;
}
