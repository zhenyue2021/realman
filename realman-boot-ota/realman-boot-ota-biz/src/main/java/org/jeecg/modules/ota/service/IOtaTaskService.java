package org.jeecg.modules.ota.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.vo.TaskCreateRequest;
import org.jeecg.modules.ota.vo.TaskDTO;
import org.jeecg.modules.ota.vo.TaskListQuery;

import java.util.Map;

/**
 * 升级任务管理，对齐 OTA 平台详细设计五章/八章（PRD 4.4、9.5）。
 */
public interface IOtaTaskService {

    TaskDTO create(TaskCreateRequest request, String operator, String operatorTenantId);

    PageResult<TaskDTO> list(TaskListQuery query);

    TaskDTO detail(String taskId);

    /** 允许前置状态：FAILED / ROLLBACK_FAILED。其他状态 409 ERR_INVALID_STATE。仅适用单设备任务。 */
    TaskDTO retry(String taskId, String operator);

    /** 允许前置状态：PENDING/PENDING_ONLINE/DOWNLOADING/CHECKING/EXTRACTING/EXECUTING（需 cancelable_in_executing）。仅适用单设备任务。 */
    TaskDTO cancel(String taskId, String operator);

    /** 允许前置状态：FAILED / ROLLBACK_FAILED；ROLLING_BACK 时 409 ERR_ROLLBACK_IN_PROGRESS。仅适用单设备任务。 */
    TaskDTO rollback(String taskId, String operator);

    /** 批量任务失败设备重试：仅重试 FAILED/ROLLBACK_FAILED 子任务。 */
    TaskDTO retryFailed(String taskId, String operator);

    /**
     * 继续 PAUSED 批量任务。{@code resumeBody} 直接透传请求体原始 Map，用于区分
     * "字段缺失"（快照冻结）与"字段显式为 null"（快照重置为初始值）——这个语义无法
     * 用普通 DTO 的装箱 Integer 表达，故在此层直接消费 Map。
     */
    TaskDTO resume(String taskId, Map<String, Object> resumeBody, String operator);

    /** 终止 PAUSED 批量任务：stop_all 未下发子任务，已下发继续跑完；用最后一次快照判定最终态。 */
    TaskDTO abort(String taskId, String operator);
}
