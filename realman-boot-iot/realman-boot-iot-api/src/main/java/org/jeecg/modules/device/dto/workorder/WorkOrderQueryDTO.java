package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

@Data
public class WorkOrderQueryDTO {

    private Integer pageNo;

    private Integer pageSize;

    /**
     * 代理商ID（可选；大多场景使用请求头 x-tenant-id 作为租户隔离）
     */
    private String agentId;

    /**
     * 工单状态：PENDING/STARTED/SUBMITTED/COMPLETED/TIMEOUT/CLOSED
     */
    private String status;

    /**
     * 工单ID（支持精确/模糊）
     */
    private String workOrderId;

    /**
     * 工单任务名称（支持模糊，来自工单自身 task_name）
     */
    private String taskName;

    /**
     * 操作员姓名（支持模糊）
     */
    private String operatorName;

    /**
     * 操作员ID（精确）
     */
    private String operatorId;

    /**
     * 主控设备（device_code / actual_device_code，支持模糊）
     */
    private String controllerCode;

    /**
     * 机器人（device_code / actual_device_code，支持模糊）
     */
    private String robotCode;

    /**
     * 计划开始时间范围（yyyy-MM-dd HH:mm:ss）
     */
    private String planStartTimeBegin;
    private String planStartTimeEnd;

    /**
     * 计划结束时间范围（yyyy-MM-dd HH:mm:ss）
     */
    private String planEndTimeBegin;
    private String planEndTimeEnd;

    /**
     * 币种（精确匹配）
     */
    private String currency;
}

