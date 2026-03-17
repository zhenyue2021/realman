package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.dto.workorder.WorkOrderCreateDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderDetailDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderPageItemDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderQueryDTO;
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

    /**
     * 分页查询工单（支持查询条件，返回列表展示 DTO）
     */
    IPage<WorkOrderPageItemDTO> pageWorkOrders(Page<WorkOrder> page, String tenantId, WorkOrderQueryDTO query);
}

