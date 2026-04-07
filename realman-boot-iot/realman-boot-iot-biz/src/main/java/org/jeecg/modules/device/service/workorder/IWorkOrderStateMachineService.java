package org.jeecg.modules.device.service.workorder;

/**
 * 工单状态机：纯领域规则（状态校验、字段更新、与操作记录联动），不含 WebSocket 等基础设施。
 */
public interface IWorkOrderStateMachineService {

    void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone);

    void submitWorkOrder(String workOrderId, String operator);

    void fillTimeoutReason(String workOrderId, String reason, String source);

    void auditWorkOrder(String workOrderId, String result, String comment, String auditor);

    void closeWorkOrder(String workOrderId, String reason, String closer);

    /**
     * 逻辑删除工单，仅 PENDING 状态允许删除。
     */
    void deleteWorkOrder(String workOrderId);
}
