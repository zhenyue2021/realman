package org.jeecg.modules.device.dto.workorder;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Darwin 工单列表/详情展示 DTO */
@Data
public class DarwinWorkOrderItemDTO {

    private String id;

    /** Darwin 侧工单 ID */
    private String darwinOrderId;

    private String taskName;

    /** 动作链描述："1.xxx，2.xxx，3.xxx" */
    private String taskDesc;

    /** 采集总条数 */
    private Integer quotaTotal;

    private String tenantId;

    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submitTime;

    private String operatorId;
    private String operatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 绑定的主控设备 */
    private WorkOrderDeviceDTO controller;

    /** 绑定的机器人设备列表 */
    private List<WorkOrderDeviceDTO> robots;
}
