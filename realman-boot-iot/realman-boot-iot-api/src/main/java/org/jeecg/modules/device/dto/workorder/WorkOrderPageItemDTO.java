package org.jeecg.modules.device.dto.workorder;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.util.List;

/**
 * 工单列表展示 DTO（用于分页列表）
 */
@Data
public class WorkOrderPageItemDTO {

    /**
     * 工单ID
     */
    private String id;

    /**
     * 所属租户信息（代理商/企业）
     */
    private String agentId;
    private String agentName;
    private String enterpriseId;
    private String enterpriseName;

    /**
     * 依赖规则（合规配置）
     */
    private String complianceId;
    /**
     * 工单任务名称
     */
    private String taskName;

    /**
     * 合规配置（规则）信息
     */
    private String taskScene;
    private Integer timeoutAlertEnabled;
    private String timeoutAlertOffset;
    private Integer taskLimitEnabled;
    private Integer acceptanceEnabled;
    private String overtimeReasonEnum;
    private String overtimeReasonDesc;
    private Integer autoCloseEnabled;
    private String autoCloseOffset;
    private Integer applyStatus;

    /**
     * 时间
     */
    private String createTime;
    private String updateTime;
    private String planStartTime;
    private String planEndTime;

    /**
     * 计费信息
     */
    private String currency;
    private String unitPrice;
    private String totalPrice;

    /**
     * 操作员
     */
    private String operatorId;
    private String operatorName;

    /**
     * 状态
     */
    @Dict(dicCode = "order_status")
    private String status;

    /**
     * 主控设备（单个）
     */
    private WorkOrderDeviceDTO controller;

    /**
     * 机器人（可多个）
     */
    private List<WorkOrderDeviceDTO> robots;
}

