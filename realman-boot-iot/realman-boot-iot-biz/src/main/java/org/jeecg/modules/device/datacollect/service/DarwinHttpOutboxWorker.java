package org.jeecg.modules.device.datacollect.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.entity.DarwinHttpOutbox;
import org.jeecg.modules.device.datacollect.http.DarwinHttpClient;
import org.jeecg.modules.device.datacollect.mapper.DarwinHttpOutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "http-enabled", havingValue = "true")
public class DarwinHttpOutboxWorker {

    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final long[] BACKOFF_SECONDS = {60L, 120L, 300L, 600L, 1800L, 3600L, 7200L};

    @Value("${darwin.integration.outbox.batch-size:50}")
    private int batchSize;
    @Value("${darwin.integration.outbox.lock-ttl-seconds:120}")
    private long lockTtlSeconds;
    @Value("${spring.application.name:iot}")
    private String instanceId;

    private final DarwinHttpOutboxMapper outboxMapper;
    private final DarwinHttpClient darwinHttpClient;

    @Scheduled(fixedDelayString = "${darwin.integration.outbox.fixed-delay-ms:10000}")
    public void replayDueOutbox() {
        LocalDateTime now = LocalDateTime.now();
        List<DarwinHttpOutbox> records = outboxMapper.selectList(Wrappers.<DarwinHttpOutbox>lambdaQuery()
                .and(w -> w.in(DarwinHttpOutbox::getStatus, "PENDING", "RETRYING")
                        .le(DarwinHttpOutbox::getNextRetryAt, now)
                        .or(o -> o.eq(DarwinHttpOutbox::getStatus, "SENDING")
                                .lt(DarwinHttpOutbox::getLockExpireAt, now)))
                .orderByAsc(DarwinHttpOutbox::getNextRetryAt)
                .last("LIMIT " + batchSize));
        for (DarwinHttpOutbox record : records) {
            processOne(record);
        }
    }

    private void processOne(DarwinHttpOutbox record) {
        if (!claim(record.getId())) {
            return;
        }
        boolean success = darwinHttpClient.replayOutbox(record);
        DarwinHttpOutbox update = new DarwinHttpOutbox();
        update.setId(record.getId());
        int attempts = safe(record.getAttemptCount()) + 1;
        update.setAttemptCount(attempts);
        if (success) {
            update.setStatus("SUCCESS");
            update.setLastError(null);
        } else if (attempts >= maxAttempts(record)) {
            update.setStatus("DEAD");
            update.setLastError("达尔文 HTTP Outbox 重放达到最大次数");
        } else {
            update.setStatus("RETRYING");
            update.setLastError("达尔文 HTTP Outbox 重放失败，等待下次重试");
            update.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
        }
        outboxMapper.updateById(update);
    }

    private boolean claim(String id) {
        LocalDateTime now = LocalDateTime.now();
        return outboxMapper.update(null, Wrappers.<DarwinHttpOutbox>lambdaUpdate()
                .set(DarwinHttpOutbox::getStatus, "SENDING")
                .set(DarwinHttpOutbox::getLockedBy, instanceId)
                .set(DarwinHttpOutbox::getLockedAt, now)
                .set(DarwinHttpOutbox::getLockExpireAt, now.plusSeconds(lockTtlSeconds))
                .eq(DarwinHttpOutbox::getId, id)
                .and(w -> w.in(DarwinHttpOutbox::getStatus, "PENDING", "RETRYING")
                        .or(o -> o.eq(DarwinHttpOutbox::getStatus, "SENDING")
                                .lt(DarwinHttpOutbox::getLockExpireAt, now)))) == 1;
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private static int maxAttempts(DarwinHttpOutbox record) {
        return record.getMaxAttempts() == null || record.getMaxAttempts() <= 0 ? DEFAULT_MAX_ATTEMPTS : record.getMaxAttempts();
    }

    private static long backoffSeconds(int attempts) {
        int index = Math.max(0, Math.min(attempts - 1, BACKOFF_SECONDS.length - 1));
        return BACKOFF_SECONDS[index];
    }
}
