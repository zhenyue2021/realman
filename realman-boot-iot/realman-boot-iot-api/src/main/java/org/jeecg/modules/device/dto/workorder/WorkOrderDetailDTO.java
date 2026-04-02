package org.jeecg.modules.device.dto.workorder;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkOrderDetailDTO {

    private String id;

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

    private String tenantId;

    private List<WorkOrderDeviceDTO> devices;
}

