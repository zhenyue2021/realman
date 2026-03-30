package org.jeecg.modules.device.scheduler;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.service.IWorkOrderSchedulerService;
import org.springframework.stereotype.Component;

/**
 * 工单相关定时任务入口：
 * <ol>
 *   <li>超时提醒</li>
 *   <li>超时标记</li>
 *   <li>超时未提交自动关闭</li>
 *   <li>开始时间到达推送</li>
 * </ol>
 * <p>Job 层只负责触发，业务逻辑均在 {@link IWorkOrderSchedulerService} 中实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkOrderSchedulerJob {

    private final IWorkOrderSchedulerService workOrderSchedulerService;

    /**
     * 超时提醒任务
     * <p>XXL-Job Handler Name：workOrderTimeoutAlertJob
     * 建议 Cron：0 0/1 * * * ?（每分钟）
     */
    @XxlJob("workOrderTimeoutAlertJob")
    public void timeoutAlert() {
        workOrderSchedulerService.timeoutAlert();
    }

    /**
     * 超时标记任务：将已过期且未提交的工单标记为 TIMEOUT
     * <p>XXL-Job Handler Name：workOrderTimeoutMarkJob
     * 建议 Cron：0 0/1 * * * ?（每分钟）
     */
    @XxlJob("workOrderTimeoutMarkJob")
    public void timeoutMark() {
        workOrderSchedulerService.timeoutMark();
    }

    /**
     * 超时未提交自动关闭任务
     * <p>XXL-Job Handler Name：workOrderAutoCloseJob
     * 建议 Cron：0 0/1 * * * ?（每分钟）
     */
    @XxlJob("workOrderAutoCloseJob")
    public void autoClose() {
        workOrderSchedulerService.autoClose();
    }

    /**
     * 工单开始时间到达推送任务 --- 一直推，直到工单开启为止
     * <p>XXL-Job Handler Name：workOrderStartPushJob
     * 建议 Cron：0 0/1 * * * ?（每分钟）
     */
    @XxlJob("workOrderStartPushJob")
    public void startTimePush() {
        workOrderSchedulerService.startTimePush();
    }
}
