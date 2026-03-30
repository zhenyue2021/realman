package org.jeecg.modules.device.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主控设备关联工单操作记录 VO
 */
@Data
public class WorkOrderOperationRecordVO {

    /** 工单 ID */
    private String workOrderId;

    /** 主控设备码 */
    private String controllerCode;

    /** 机器人设备码 */
    private String robotDeviceCode;

    /** 操作开始时间（work_order.actual_start_time） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operatorStartTime;

    /** 操作结束时间（work_order.submit_time） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operatorEndTime;

    /** 操作时长（秒），submit_time 为空时为 null */
    private Long durationSeconds;
}
