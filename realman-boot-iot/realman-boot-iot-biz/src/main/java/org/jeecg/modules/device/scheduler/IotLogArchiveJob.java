package org.jeecg.modules.device.scheduler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.service.IotLogArchiveService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * IoT 日志归档定时任务
 *
 * <p>将以下主表中超过保留期的数据迁移至历史表，再从主表删除：
 * <ul>
 *   <li>{@code iot_device_command_record} — 按 {@code create_time}</li>
 *   <li>{@code iot_device_operation_log} — 按 {@code operation_time}</li>
 *   <li>{@code iot_mq_message_log} — 按 {@code create_time}</li>
 * </ul>
 *
 * <p>历史表（{@code *_history}）中 {@code backup_time} 超过 {@code history-retention-days} 的数据将被物理删除。
 *
 * <p>配置项：
 * <pre>
 *   iot.log.archive.retention-days=90           # 主表保留天数，默认 90（约 3 个月）
 *   iot.log.archive.history-retention-days=365  # 历史表保留天数，默认 365
 *   iot.log.archive.batch-size=2000             # 每批归档/删除行数
 * </pre>
 *
 * <p>部署前需执行 DDL：{@code db/scripts/iot_log_archive_ddl.sql}
 *
 * <p>XXL-Job Handler Name：{@code iotLogArchiveJob}，建议 Cron：{@code 0 10 3 * * ?}（每天 03:10）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IotLogArchiveJob {

    private final IotLogArchiveService logArchiveService;

    @Value("${iot.log.archive.retention-days:90}")
    private int retentionDays;

    @Value("${iot.log.archive.history-retention-days:365}")
    private int historyRetentionDays;

    @XxlJob("iotLogArchiveJob")
    public void archiveLogs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime mainCutoff = now.minusDays(retentionDays);
        LocalDateTime historyCutoff = now.minusDays(historyRetentionDays);

        log.info("[LogArchive] 开始归档，主表截止={}, 历史表清理截止={}", mainCutoff, historyCutoff);

        IotLogArchiveService.ArchiveSummary summary =
                logArchiveService.archiveAll(mainCutoff, historyCutoff);

        String message = String.format(
                "[LogArchive] 完成 — 主表归档 %d 条（指令=%d, 操作=%d, MQ=%d），历史表清理 %d 条",
                summary.totalArchived(),
                summary.getCommandRecordArchived(),
                summary.getOperationLogArchived(),
                summary.getMqMessageLogArchived(),
                summary.totalHistoryPurged());
        log.info(message);
        XxlJobHelper.log(message);
    }
}
