package org.jeecg.modules.device.service.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.dto.WorkOrderOperationRecordDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;

import java.util.List;

public interface IWorkOrderService extends IService<WorkOrder> {

    IPage<WorkOrder> pageWorkOrders(Page<WorkOrder> page, String agentId, String status);

    List<WorkOrder> listForExport(String agentId, String status);

    List<WorkOrder> listPendingForController(String controllerCode);

    /**
     * 查询指定主控 + 指定部门范围内进行中（STARTED）和待开始（PENDING）且生效的工单（按计划开始时间升序）。
     */
    List<WorkOrder> listPendingForControllerAndDepartments(String controllerCode, List<String> departmentIds);

    void bindDevices(String workOrderId, List<WorkOrderDevice> devices);
    List<WorkOrderDevice> findDevices(String workOrderId);
    WorkOrderDevice findMasterDevice(String workOrderId);
    void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone,
                        String controllerCode, String robotCode);

    void submitWorkOrder(String workOrderId, String operator);

    void fillTimeoutReason(String workOrderId, String reason, String source);

    void auditWorkOrder(String workOrderId, String result, String comment, String auditor);

    void closeWorkOrder(String workOrderId, String reason, String closer);

    /**
     * 逻辑删除工单，仅 PENDING 状态允许删除。
     */
    void deleteWorkOrder(String workOrderId);

    WorkOrder createWorkOrderWithDevices(WorkOrder order, List<WorkOrderDevice> devices);

    WorkOrder editWorkOrderWithDevices(WorkOrder updated, List<WorkOrderDevice> devices);

    /**
     * 将所有已开启且未超时（status=STARTED，planEndTime > now）的工单，
     * 通过 WebSocket 推送到对应主控前端。
     * 由定时任务每分钟调用，保持前端实时感知进行中的工单。
     */
    void pushStartedWorkOrders();

    /**
     * 按主控设备码分页查询关联工单操作记录
     */
    IPage<WorkOrderOperationRecordDTO> pageWorkOrderOperationRecords(
            Page<WorkOrder> page,
            String controllerCode);

    /**
     * 达尔文平台工单 upsert：workOrderId（外层 id）不存在时新建，已存在时更新。
     * workOrderId 即达尔文工单 ID，直接用作 work_order 表主键。
     * deviceCode 为执行该工单的机器人设备编码，写入 work_order_device（ROBOT 类型）。
     */
    WorkOrder upsertWorkOrderFromDarwin(String workOrderId, String tenant,
                                        WorkOrderCreateMsg.WorkOrderItem item, String traceId,
                                        String deviceCode);

    /**
     * 达尔文侧删除工单（deleted=true）：按 workOrderId（= work_order.id）软删除。
     * 幂等：不存在时静默跳过。
     */
    void deleteWorkOrderFromDarwin(String workOrderId);

    /**
     * 将 Darwin 的 PENDING 和 STARTED 工单通过 WebSocket 推送给指定机器人设备。
     * 设备无 Darwin 工单时静默返回。
     */
    void pushDarwinWorkOrdersForDevice(String deviceCode);
}

