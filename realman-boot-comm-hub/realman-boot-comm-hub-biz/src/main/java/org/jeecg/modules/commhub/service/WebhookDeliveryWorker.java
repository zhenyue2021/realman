package org.jeecg.modules.commhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.commhub.entity.DeviceUplinkEventLog;
import org.jeecg.modules.commhub.entity.WebhookDeliveryTask;
import org.jeecg.modules.commhub.entity.WebhookSubscription;
import org.jeecg.modules.commhub.mapper.DeviceUplinkEventLogMapper;
import org.jeecg.modules.commhub.mapper.WebhookDeliveryTaskMapper;
import org.jeecg.modules.commhub.mapper.WebhookSubscriptionMapper;
import org.jeecg.modules.commhub.vo.UplinkEventDTO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 扫描到期 Webhook 投递任务并执行单次 HTTP 推送。
 * 重试退避通过 next_retry_at 持久化调度，不占用线程 sleep。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryWorker {

    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_SECONDS = {1L, 2L, 5L, 10L};
    private static final int DEFAULT_BATCH_SIZE = 50;

    private final WebhookDeliveryTaskMapper deliveryTaskMapper;
    private final DeviceUplinkEventLogMapper eventLogMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookDispatchClient dispatchClient;
    private final IWebhookSubscriptionService webhookSubscriptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelayString = "${comm-hub.webhook-delivery.scan-interval-ms:1000}")
    public void scanAndDeliver() {
        LocalDateTime now = LocalDateTime.now();
        Page<WebhookDeliveryTask> page = new Page<>(1, DEFAULT_BATCH_SIZE);
        List<WebhookDeliveryTask> tasks = deliveryTaskMapper.selectPage(page, Wrappers.<WebhookDeliveryTask>lambdaQuery()
                .in(WebhookDeliveryTask::getStatus, "PENDING", "RETRYING")
                .le(WebhookDeliveryTask::getNextRetryAt, now)
                .orderByAsc(WebhookDeliveryTask::getNextRetryAt)
                .orderByAsc(WebhookDeliveryTask::getCreatedAt)).getRecords();
        for (WebhookDeliveryTask task : tasks) {
            deliver(task);
        }
    }

    private void deliver(WebhookDeliveryTask task) {
        if (!markSending(task)) {
            return;
        }
        WebhookSubscription subscription = subscriptionMapper.selectById(task.getSubscriptionId());
        if (subscription == null || !StringUtils.hasText(subscription.getHmacSecret())) {
            markFailed(task, "Webhook subscription or hmacSecret not found");
            return;
        }
        DeviceUplinkEventLog eventLog = eventLogMapper.selectById(task.getEventLogId());
        if (eventLog == null) {
            markFailed(task, "Device uplink event log not found");
            return;
        }
        try {
            dispatchClient.dispatchOnce(task.getCallbackUrl(), subscription.getHmacSecret(), objectMapper.writeValueAsString(toDTO(eventLog)));
            markSuccess(task);
        } catch (Exception e) {
            markRetryOrFailed(task, e.getMessage());
        }
    }

    private boolean markSending(WebhookDeliveryTask task) {
        LocalDateTime now = LocalDateTime.now();
        WebhookDeliveryTask update = new WebhookDeliveryTask();
        update.setStatus("SENDING");
        update.setUpdatedAt(now);
        return deliveryTaskMapper.update(update, Wrappers.<WebhookDeliveryTask>lambdaUpdate()
                .eq(WebhookDeliveryTask::getId, task.getId())
                .in(WebhookDeliveryTask::getStatus, "PENDING", "RETRYING")) > 0;
    }

    private void markSuccess(WebhookDeliveryTask task) {
        deliveryTaskMapper.update(null, Wrappers.<WebhookDeliveryTask>lambdaUpdate()
                .set(WebhookDeliveryTask::getStatus, "SUCCESS")
                .set(WebhookDeliveryTask::getLastError, null)
                .set(WebhookDeliveryTask::getUpdatedAt, LocalDateTime.now())
                .eq(WebhookDeliveryTask::getId, task.getId())
                .eq(WebhookDeliveryTask::getStatus, "SENDING"));
        webhookSubscriptionService.recordDispatchResult(task.getSubscriptionId(), true);
    }

    private void markRetryOrFailed(WebhookDeliveryTask task, String errorMessage) {
        int nextAttemptCount = (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1;
        if (nextAttemptCount >= MAX_ATTEMPTS) {
            markFailed(task, errorMessage);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        WebhookDeliveryTask update = new WebhookDeliveryTask();
        update.setStatus("RETRYING");
        update.setAttemptCount(nextAttemptCount);
        update.setNextRetryAt(now.plusSeconds(BACKOFF_SECONDS[nextAttemptCount - 1]));
        update.setLastError(truncate(errorMessage));
        update.setUpdatedAt(now);
        deliveryTaskMapper.update(update, Wrappers.<WebhookDeliveryTask>lambdaUpdate()
                .eq(WebhookDeliveryTask::getId, task.getId())
                .eq(WebhookDeliveryTask::getStatus, "SENDING"));
        log.warn("[comm-hub] Webhook 推送失败，等待重试 taskId={} attempt={}/{} nextRetryAt={} error={}",
                task.getId(), nextAttemptCount, MAX_ATTEMPTS, update.getNextRetryAt(), errorMessage);
    }

    private void markFailed(WebhookDeliveryTask task, String errorMessage) {
        int nextAttemptCount = (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1;
        WebhookDeliveryTask update = new WebhookDeliveryTask();
        update.setStatus("FAILED");
        update.setAttemptCount(Math.min(nextAttemptCount, MAX_ATTEMPTS));
        update.setLastError(truncate(errorMessage));
        update.setUpdatedAt(LocalDateTime.now());
        deliveryTaskMapper.update(update, Wrappers.<WebhookDeliveryTask>lambdaUpdate()
                .eq(WebhookDeliveryTask::getId, task.getId())
                .eq(WebhookDeliveryTask::getStatus, "SENDING"));
        webhookSubscriptionService.recordDispatchResult(task.getSubscriptionId(), false);
        log.error("[comm-hub] Webhook 推送最终失败 taskId={} subscriptionId={} error={}",
                task.getId(), task.getSubscriptionId(), errorMessage);
    }

    private UplinkEventDTO toDTO(DeviceUplinkEventLog entry) {
        UplinkEventDTO dto = new UplinkEventDTO();
        dto.setId(entry.getId());
        dto.setDeviceId(entry.getDeviceId());
        dto.setDeviceCode(entry.getDeviceCode());
        dto.setDeviceType(entry.getDeviceType());
        dto.setTenantId(entry.getTenantId());
        dto.setEventKind(entry.getEventKind());
        dto.setTransport(entry.getTransport());
        dto.setReportedAt(entry.getReportedAt());
        if (StringUtils.hasText(entry.getPayload())) {
            try {
                dto.setPayload(objectMapper.readValue(entry.getPayload(), new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception e) {
                dto.setPayload(Collections.emptyMap());
            }
        }
        return dto;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
