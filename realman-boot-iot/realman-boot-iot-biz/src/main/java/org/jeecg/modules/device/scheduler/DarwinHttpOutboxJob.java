package org.jeecg.modules.device.scheduler;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.http.DarwinHttpClient;
import org.jeecg.modules.device.datacollect.outbox.DarwinHttpOutbox;
import org.jeecg.modules.device.datacollect.outbox.DarwinHttpOutboxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Darwin HTTP outbox 补偿任务：扫描 PENDING/FAILED_RETRYABLE 且到达 next_retry_at 的事件型请求，
 * 按指数退避重新调用 Darwin HTTP 接口，实现 HTTP 替代 RocketMQ 后的至少一次投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "http-enabled", havingValue = "true")
public class DarwinHttpOutboxJob {

    private static final int BATCH_SIZE = 100;

    private final DarwinHttpOutboxService outboxService;
    private final DarwinHttpClient darwinHttpClient;

    /**
     * <p>XXL-Job Handler Name：{@code darwinHttpOutboxRetryJob}，建议 Cron：{@code 0 0/1 * * * ?}
     */
    @XxlJob("darwinHttpOutboxRetryJob")
    public void retry() {
        for (DarwinHttpOutbox item : outboxService.listDue(BATCH_SIZE)) {
            if (darwinHttpClient.retryOutbox(item)) {
                outboxService.markSucceeded(item.getId());
                log.info("[DataCollect][HTTP][Outbox] 补偿成功 id={} path={} deviceCode={}",
                        item.getId(), item.getPath(), item.getDeviceCode());
            } else {
                outboxService.markRetryableFailure(item, "Darwin HTTP outbox retry failed");
            }
        }
    }
}
