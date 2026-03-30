package org.jeecg.modules.device.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单操作记录查询结果（biz 层 Mapper 出参，不依赖 api 模块）
 * <p>由 iot-api 层的 MasterDeviceApiServiceImpl 转换为 WorkOrderOperationRecordVO 后对外返回。
 */
@Data
public class WorkOrderOperationRecordDTO {

    private String workOrderId;

    private String controllerCode;

    private String robotDeviceCode;

    private LocalDateTime actualStartTime;

    private LocalDateTime submitTime;

    private Long durationSeconds;
}
