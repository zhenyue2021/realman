package org.jeecg.modules.device.datacollect.dto.http;

import lombok.Data;

/** 工单创建/更新的单项结果，见 {@code DarwinIntegrationController#createDataCollectTask}。 */
@Data
public class WorkOrderItemResult {

    private String id;
    private boolean success;
    private String message;

    public static WorkOrderItemResult ok(String id) {
        WorkOrderItemResult result = new WorkOrderItemResult();
        result.setId(id);
        result.setSuccess(true);
        return result;
    }

    public static WorkOrderItemResult fail(String id, String message) {
        WorkOrderItemResult result = new WorkOrderItemResult();
        result.setId(id);
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
