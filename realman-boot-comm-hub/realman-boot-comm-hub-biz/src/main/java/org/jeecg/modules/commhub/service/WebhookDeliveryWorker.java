package org.jeecg.modules.commhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.commhub.entity.WebhookDeliveryTask;
import org.jeecg.modules.commhub.mapper.WebhookDeliveryTaskMapper;
import org.jeecg.modules.commhub.vo.WebhookDispatchResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryWorker {

    private static final int BATCH_SIZE = 50;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_SECONDS = {60L, 120L, 300L, 600L};

    private final WebhookDeliveryTaskMapper taskMapper;
    private final WebhookDispatchClient dispatchClient;
    private final IWebhookSubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 5000L)
    public void dispatchDueTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<WebhookDeliveryTask> tasks = taskMapper.selectList(Wrappers.<WebhookDeliveryTask>lambdaQuery()
                .in(WebhookDeliveryTask::getStatus, "PENDING", "RETRYING")
                .le(WebhookDeliveryTask::getNextRetryAt, now)
                .orderByAsc(WebhookDeliveryTask::getNextRetryAt)
                .last("LIMIT " + BATCH_SIZE));
        for (WebhookDeliveryTask task : tasks) {
            processOne(task);
        }
    }

    private void processOne(WebhookDeliveryTask task) {
        if (!claim(task.getId())) {
            return;
        }
        WebhookDispatchResult result = dispatchClient.dispatchOnce(task.getCallbackUrl(), task.getHmacSecret(), task.getRequestBody());
        WebhookDeliveryTask update = new WebhookDeliveryTask();
        update.setId(task.getId());
        int attempts = safe(task.getAttemptCount()) + 1;
        update.setAttemptCount(attempts);
        update.setLastStatusCode(result.getStatusCode());
        update.setLastError(result.getErrorMessage());
        if (result.isSuccess()) {
            update.setStatus("SUCCESS");
            subscriptionService.recordDispatchResult(task.getSubscriptionId(), true);
        } else if (attempts >= maxAttempts(task)) {
            update.setStatus("DEAD");
            subscriptionService.recordDispatchResult(task.getSubscriptionId(), false);
        } else {
            update.setStatus("RETRYING");
            update.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
        }
        taskMapper.updateById(update);
    }

    private boolean claim(String id) {
        return taskMapper.update(null, Wrappers.<WebhookDeliveryTask>lambdaUpdate()
                .set(WebhookDeliveryTask::getStatus, "SENDING")
                .eq(WebhookDeliveryTask::getId, id)
                .in(WebhookDeliveryTask::getStatus, "PENDING", "RETRYING")) == 1;
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private static int maxAttempts(WebhookDeliveryTask task) {
        return task.getMaxAttempts() == null || task.getMaxAttempts() <= 0 ? DEFAULT_MAX_ATTEMPTS : task.getMaxAttempts();
    }

    private static long backoffSeconds(int attempts) {
        int index = Math.max(0, Math.min(attempts - 1, BACKOFF_SECONDS.length - 1));
        return BACKOFF_SECONDS[index];
    }
}
