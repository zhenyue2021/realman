package org.jeecg.modules.device.dto.workorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class WorkOrderCreateDTO {

    /**
     * 工单任务名称
     */
    @NotEmpty(message = "工单名称不能为空")
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
    @NotEmpty(message = "租户信息不能为空-agentId")
    private String agentId;
    @NotEmpty(message = "租户信息不能为空-agentName")
    private String agentName;
    @NotEmpty(message = "部门信息不能为空-departmentId")
    private String departmentId;
    @NotEmpty(message = "部门信息不能为空-departmentName")
    private String departmentName;
    @NotEmpty(message = "工单合规配置不能为空-complianceId")
    private String complianceId;

    private String remark;
    @NotEmpty(message = "工单计划开始时间不能为空-complianceId")
    private String planStartTime;
    @NotEmpty(message = "工单计划结束时间不能为空-complianceId")
    private String planEndTime;

    private String tenantId;
    @NotEmpty(message = "绑定设备不能为空")
    private List<WorkOrderDeviceDTO> devices;
}

