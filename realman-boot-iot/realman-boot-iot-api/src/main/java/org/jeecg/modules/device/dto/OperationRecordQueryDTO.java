package org.jeecg.modules.device.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作记录分页/导出查询条件
 */
@Data
public class OperationRecordQueryDTO {

    private Integer pageNo;
    private Integer pageSize;

    /** 主控设备ID */
    private String controllerId;
    /** 主控设备编号 */
    private String controllerCode;
    /** 机器人设备ID */
    private String robotId;
    /** 开始操作时间-起 */
    private LocalDateTime startTimeFrom;
    /** 开始操作时间-止 */
    private LocalDateTime startTimeTo;
}
