package org.jeecg.modules.device.service.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;

import java.util.List;

public interface IWorkOrderService extends IService<WorkOrder> {

    IPage<WorkOrder> pageWorkOrders(Page<WorkOrder> page, String agentId, String status);

    List<WorkOrder> listForExport(String agentId, String status);

    List<WorkOrder> listPendingForController(String controllerCode);

    void bindDevices(String workOrderId, List<WorkOrderDevice> devices);
    List<WorkOrderDevice> findDevices(String workOrderId);

    void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone);

    void submitWorkOrder(String workOrderId);

    void fillTimeoutReason(String workOrderId, String reason, String source);

    void auditWorkOrder(String workOrderId, String result, String comment, String auditor);

    void closeWorkOrder(String workOrderId, String reason, String closer);

    WorkOrder createWorkOrderWithDevices(WorkOrder order, List<WorkOrderDevice> devices);

    WorkOrder editWorkOrderWithDevices(WorkOrder updated, List<WorkOrderDevice> devices);
}

