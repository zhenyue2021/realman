package org.jeecg.modules.device.dto.workorder;

import lombok.Data;

/** Darwin 工单分页查询参数 */
@Data
public class DarwinWorkOrderQueryDTO {

    private Integer pageNo;
    private Integer pageSize;

    /** 工单状态过滤（PENDING / STARTED / SUBMITTED / APPROVED / REJECTED / CLOSED） */
    private String status;

    /** 任务名称模糊搜索 */
    private String taskName;
}
