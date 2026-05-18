package org.jeecg.modules.device.dto.workorder;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单列表展示 DTO（用于分页列表）
 */
@Data
public class WorkOrderPageItemDTO {

    private String id;

    private String taskName;

    private String agentId;

    private String agentName;

    private String departmentId;

    private String departmentName;

    private String complianceId;

    private String currency;

    private String unitPrice;

    private String totalPrice;

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
    private String source;

    private String createBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    private String updateBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 工单合规配置
     */
    private WorkOrderComplianceConfigDetailDTO compliance;
    /**
     * 主控设备（单个）
     */
    private WorkOrderDeviceDTO controller;

    /**
     * 机器人（可多个）
     */
    private List<WorkOrderDeviceDTO> robots;
}

