package org.jeecg.modules.device.api;

import org.jeecg.modules.device.dto.workorder.WorkOrderCreateDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderDetailDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrder;

public interface WorkOrderApiService {

    /**
     * 创建工单（包含绑定设备）
     */
    WorkOrder create(WorkOrderCreateDTO dto, String operator);

    /**
     * 编辑工单（基础信息与绑定设备）
     */
    WorkOrder edit(String workOrderId, WorkOrderCreateDTO dto, String operator);

    /**
     * 获取工单详情
     */
    WorkOrderDetailDTO getWorkOrderDetail(String workOrderId);
}

