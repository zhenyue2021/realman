package org.jeecg.modules.device.service;

/**
 * 工单定时任务业务逻辑接口
 * <p>
 * 将各定时任务的核心业务逻辑从 Job 层下沉至 Service 层，
 * Job 只负责触发入口，具体实现均在此接口的实现类中。
 */
public interface IWorkOrderSchedulerService {

    /**
     * 工单超时提醒
     * <p>
     * 扫描即将超时（计划结束时间在未来1小时内）的 PENDING/STARTED 工单，
     * 按合规配置判断是否需要发出提醒。
     */
    void timeoutAlert();

    /**
     * 工单超时标记
     * <p>
     * 将已过期且未提交的工单状态置为 TIMEOUT，
     * 仅对合规配置启用了任务限制（taskLimitEnabled=1）且未开启超时提交（overtimeEnabled≠1）的工单生效。
     */
    void timeoutMark();

    /**
     * 超时未填原因工单自动关闭
     * <p>
     * 对状态为 TIMEOUT 且未填写超时原因的工单，
     * 在超出合规配置自动关闭偏移时间后，由系统自动置为 CLOSED。
     */
    void autoClose();

    /**
     * 工单开始时间到达推送
     * <p>
     * 当工单计划开始时间到达后，将工单信息通过 WebSocket 推送给对应主控端。
     * 使用 Redis 去重，保证同一工单仅推送一次；推送异常时删除去重 Key 以允许重试。
     */
    void startTimePush();
}
