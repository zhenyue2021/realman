package org.jeecg.modules.device.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mapper.IotLogArchiveMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;

/**
 * IoT 日志归档服务：将主表超期数据迁移至历史表，并清理历史表超期数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotLogArchiveService {

    private final IotLogArchiveMapper logArchiveMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${iot.log.archive.batch-size:2000}")
    private int batchSize;

    public ArchiveSummary archiveAll(LocalDateTime mainCutoff, LocalDateTime historyCutoff) {
        ArchiveSummary summary = new ArchiveSummary();
        summary.add("commandRecord", archiveCommandRecords(mainCutoff));
        summary.add("operationLog", archiveOperationLogs(mainCutoff));
        summary.add("mqMessageLog", archiveMqMessageLogs(mainCutoff));

        summary.addHistoryPurged("commandRecordHistory",
                purgeHistory(historyCutoff, logArchiveMapper::deleteCommandRecordHistoryBefore));
        summary.addHistoryPurged("operationLogHistory",
                purgeHistory(historyCutoff, logArchiveMapper::deleteOperationLogHistoryBefore));
        summary.addHistoryPurged("mqMessageLogHistory",
                purgeHistory(historyCutoff, logArchiveMapper::deleteMqMessageLogHistoryBefore));
        return summary;
    }

    public int archiveCommandRecords(LocalDateTime cutoff) {
        return archiveTable(cutoff, logArchiveMapper::selectCommandRecordIdsBefore,
                ids -> transactionTemplate.execute(status -> {
                    int inserted = logArchiveMapper.insertCommandRecordHistory(ids);
                    int deleted = logArchiveMapper.deleteCommandRecordsByIds(ids);
                    logBatchMismatch("iot_device_command_record", inserted, deleted);
                    return deleted;
                }));
    }

    public int archiveOperationLogs(LocalDateTime cutoff) {
        return archiveTable(cutoff, logArchiveMapper::selectOperationLogIdsBefore,
                ids -> transactionTemplate.execute(status -> {
                    int inserted = logArchiveMapper.insertOperationLogHistory(ids);
                    int deleted = logArchiveMapper.deleteOperationLogsByIds(ids);
                    logBatchMismatch("iot_device_operation_log", inserted, deleted);
                    return deleted;
                }));
    }

    public int archiveMqMessageLogs(LocalDateTime cutoff) {
        return archiveTable(cutoff, logArchiveMapper::selectMqMessageLogIdsBefore,
                ids -> transactionTemplate.execute(status -> {
                    int inserted = logArchiveMapper.insertMqMessageLogHistory(ids);
                    int deleted = logArchiveMapper.deleteMqMessageLogsByIds(ids);
                    logBatchMismatch("iot_mq_message_log", inserted, deleted);
                    return deleted;
                }));
    }

    private int archiveTable(LocalDateTime cutoff,
                             BiFunction<LocalDateTime, Integer, List<String>> idSelector,
                             java.util.function.Function<List<String>, Integer> archiver) {
        int total = 0;
        List<String> ids;
        do {
            ids = idSelector.apply(cutoff, batchSize);
            if (CollectionUtils.isEmpty(ids)) {
                break;
            }
            Integer archived = archiver.apply(ids);
            total += archived != null ? archived : 0;
        } while (ids.size() >= batchSize);
        return total;
    }

    private int purgeHistory(LocalDateTime cutoff,
                             BiFunction<LocalDateTime, Integer, Integer> deleter) {
        int total = 0;
        int deleted;
        do {
            deleted = deleter.apply(cutoff, batchSize);
            total += deleted;
        } while (deleted >= batchSize);
        return total;
    }

    private void logBatchMismatch(String table, int inserted, int deleted) {
        if (inserted != deleted) {
            log.warn("[LogArchive] {} 归档批次不一致 inserted={} deleted={}", table, inserted, deleted);
        }
    }

    @Getter
    public static class ArchiveSummary {
        private int commandRecordArchived;
        private int operationLogArchived;
        private int mqMessageLogArchived;
        private int commandRecordHistoryPurged;
        private int operationLogHistoryPurged;
        private int mqMessageLogHistoryPurged;

        void add(String table, int count) {
            switch (table) {
                case "commandRecord" -> commandRecordArchived = count;
                case "operationLog" -> operationLogArchived = count;
                case "mqMessageLog" -> mqMessageLogArchived = count;
                default -> { }
            }
        }

        void addHistoryPurged(String table, int count) {
            switch (table) {
                case "commandRecordHistory" -> commandRecordHistoryPurged = count;
                case "operationLogHistory" -> operationLogHistoryPurged = count;
                case "mqMessageLogHistory" -> mqMessageLogHistoryPurged = count;
                default -> { }
            }
        }

        public int totalArchived() {
            return commandRecordArchived + operationLogArchived + mqMessageLogArchived;
        }

        public int totalHistoryPurged() {
            return commandRecordHistoryPurged + operationLogHistoryPurged + mqMessageLogHistoryPurged;
        }

        @Override
        public String toString() {
            return String.format(
                    "archived(command=%d, operation=%d, mq=%d), historyPurged(command=%d, operation=%d, mq=%d)",
                    commandRecordArchived, operationLogArchived, mqMessageLogArchived,
                    commandRecordHistoryPurged, operationLogHistoryPurged, mqMessageLogHistoryPurged);
        }
    }
}
