package org.jeecg.modules.device.datacollect.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DarwinHttpOutboxService {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final int MAX_ERROR_LEN = 500;

    private final DarwinHttpOutboxMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public void upsertRetryableFailure(String path, String requestBody, String deviceCode, String error) {
        Date now = new Date();
        DarwinHttpOutbox existing = mapper.selectOne(new LambdaQueryWrapper<DarwinHttpOutbox>()
                .eq(DarwinHttpOutbox::getPath, path)
                .eq(DarwinHttpOutbox::getRequestBody, requestBody)
                .last("limit 1"));
        if (existing == null) {
            mapper.insert(new DarwinHttpOutbox()
                    .setPath(path)
                    .setRequestBody(requestBody)
                    .setDeviceCode(deviceCode)
                    .setStatus(STATUS_PENDING)
                    .setAttemptCount(0)
                    .setNextRetryAt(now)
                    .setLastError(truncate(error))
                    .setCreatedAt(now));
            return;
        }
        mapper.update(null, new LambdaUpdateWrapper<DarwinHttpOutbox>()
                .eq(DarwinHttpOutbox::getId, existing.getId())
                .set(DarwinHttpOutbox::getStatus, STATUS_FAILED_RETRYABLE)
                .set(DarwinHttpOutbox::getNextRetryAt, now)
                .set(DarwinHttpOutbox::getLastError, truncate(error)));
    }

    public List<DarwinHttpOutbox> listDue(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<DarwinHttpOutbox>()
                .in(DarwinHttpOutbox::getStatus, STATUS_PENDING, STATUS_FAILED_RETRYABLE)
                .le(DarwinHttpOutbox::getNextRetryAt, new Date())
                .orderByAsc(DarwinHttpOutbox::getNextRetryAt)
                .last("limit " + limit));
    }

    public void markSucceeded(String id) {
        mapper.update(null, new LambdaUpdateWrapper<DarwinHttpOutbox>()
                .eq(DarwinHttpOutbox::getId, id)
                .set(DarwinHttpOutbox::getStatus, STATUS_SUCCEEDED));
    }

    public void markRetryableFailure(DarwinHttpOutbox item, String error) {
        int attempts = item.getAttemptCount() == null ? 1 : item.getAttemptCount() + 1;
        mapper.update(null, new LambdaUpdateWrapper<DarwinHttpOutbox>()
                .eq(DarwinHttpOutbox::getId, item.getId())
                .set(DarwinHttpOutbox::getStatus, STATUS_FAILED_RETRYABLE)
                .set(DarwinHttpOutbox::getAttemptCount, attempts)
                .set(DarwinHttpOutbox::getNextRetryAt, new Date(System.currentTimeMillis() + backoffMillis(attempts)))
                .set(DarwinHttpOutbox::getLastError, truncate(error)));
    }

    private long backoffMillis(int attempts) {
        int minutes = Math.min(60, 1 << Math.min(attempts, 6));
        return minutes * 60_000L;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "...";
    }
}
