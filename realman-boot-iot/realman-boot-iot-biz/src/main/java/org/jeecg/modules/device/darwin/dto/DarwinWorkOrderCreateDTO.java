package org.jeecg.modules.device.darwin.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class DarwinWorkOrderCreateDTO {

    private String traceId;
    /** 达尔文工单 ID（幂等 Key） */
    private String darwinOrderId;
    private String darwinAgentId;
    private String darwinAgentName;
    private String darwinDeptId;
    private String darwinDeptName;
    private String taskName;
    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    /** 关联设备码列表（平台内 deviceCode） */
    private List<String> deviceCodes;
    private BigDecimal unitPrice;
    private String remark;
}
