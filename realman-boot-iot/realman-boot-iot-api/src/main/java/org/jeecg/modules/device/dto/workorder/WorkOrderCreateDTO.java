package org.jeecg.modules.device.dto.workorder;

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

    private LocalDateTime planStartTime;

    private LocalDateTime planEndTime;

    private List<WorkOrderDeviceDTO> devices;
}

