package org.jeecg.modules.device.dto.workorder;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkOrderCreateDTO {

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

