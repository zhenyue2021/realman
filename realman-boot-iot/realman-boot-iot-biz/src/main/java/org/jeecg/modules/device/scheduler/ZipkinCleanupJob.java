package org.jeecg.modules.device.scheduler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Zipkin 链路数据定时清理任务
 *
 * <p>Zipkin 使用 MySQL 存储时没有内置的数据过期机制，长期运行会导致
 * {@code zipkin_spans} / {@code zipkin_annotations} / {@code zipkin_dependencies}
 * 无限增长。本任务每天凌晨执行一次，删除超过保留期的历史数据。
 *
 * <p>清理顺序（避免外键约束问题）：
 * <ol>
 *   <li>zipkin_annotations — 按 a_timestamp（微秒）过滤</li>
 *   <li>zipkin_spans       — 按 start_ts（微秒）过滤</li>
 *   <li>zipkin_dependencies — 按 day（DATE）过滤</li>
 * </ol>
 *
 * <p>配置项：
 * <pre>
 *   zipkin.cleanup.retention-days=7        # 数据保留天数，默认 7 天
 *   zipkin.cleanup.database=zipkin         # Zipkin 所在的数据库名，默认 zipkin
 *   zipkin.cleanup.batch-size=5000         # 每批删除行数，避免大事务锁表，默认 5000
 * </pre>
 *
 * <p>XXL-Job Handler Name：{@code zipkinCleanupJob}，建议 Cron：{@code 0 0 3 * * ?}（每天 03:00）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZipkinCleanupJob {

    private final JdbcTemplate jdbcTemplate;

    /** 数据保留天数，超过此天数的 Span 数据将被物理删除 */
    @Value("${zipkin.cleanup.retention-days:7}")
    private int retentionDays;

    /** Zipkin 所在数据库名（与应用业务库分离时通过此配置指定） */
    @Value("${zipkin.cleanup.database:zipkin}")
    private String zipkinDatabase;

    /** 每批删除行数；过大会产生长事务锁表，过小会导致任务运行时间过长 */
    @Value("${zipkin.cleanup.batch-size:5000}")
    private int batchSize;

    /**
     * Zipkin 历史数据清理
     *
     * <p>XXL-Job Handler Name：{@code zipkinCleanupJob}
     * <p>建议 Cron：{@code 0 0 3 * * ?}（每天 03:00，业务低峰期）
     */
    @XxlJob("zipkinCleanupJob")
    public void cleanupZipkinData() {
        // 截止时间点：当前时刻减去保留天数
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        // zipkin_spans.start_ts / zipkin_annotations.a_timestamp 均为微秒时间戳
        long cutoffMicros = cutoff.getEpochSecond() * 1_000_000L + cutoff.getNano() / 1_000L;
        // zipkin_dependencies.day 为 DATE 类型，直接用日期字符串比较
        String cutoffDate = cutoff.toString().substring(0, 10); // yyyy-MM-dd

        log.info("[ZipkinCleanup] 开始清理，保留天数={}, 截止时间={}, 数据库={}",
                retentionDays, cutoff, zipkinDatabase);

        int totalAnnotations  = deleteAnnotations(cutoffMicros);
        int totalSpans        = deleteSpans(cutoffMicros);
//        int totalDependencies = deleteDependencies(cutoffDate);

        String summary = String.format(
                "[ZipkinCleanup] 清理完成 — annotations=%d, spans=%d, dependencies=%d",
                totalAnnotations, totalSpans, 0);
        log.info(summary);
        XxlJobHelper.log(summary);
    }

    // ── 分批删除：annotations ────────────────────────────────────────────────

    private int deleteAnnotations(long cutoffMicros) {
        String sql = String.format(
                "DELETE FROM `%s`.`zipkin_annotations` WHERE a_timestamp < ? LIMIT %d",
                zipkinDatabase, batchSize);
        return deleteBatch("annotations", sql, cutoffMicros);
    }

    // ── 分批删除：spans ──────────────────────────────────────────────────────

    private int deleteSpans(long cutoffMicros) {
        String sql = String.format(
                "DELETE FROM `%s`.`zipkin_spans` WHERE start_ts < ? LIMIT %d",
                zipkinDatabase, batchSize);
        return deleteBatch("spans", sql, cutoffMicros);
    }

    // ── 分批删除：dependencies ──统计表不删除─────────────────────────────────────────────

    private int deleteDependencies(String cutoffDate) {
        String sql = String.format(
                "DELETE FROM `%s`.`zipkin_dependencies` WHERE day < ? LIMIT %d",
                zipkinDatabase, batchSize);
        return deleteBatch("dependencies", sql, cutoffDate);
    }

    /**
     * 通用分批删除：循环执行 DELETE ... LIMIT N 直到本批次删除行数为 0。
     *
     * @param tableName 日志用表名
     * @param sql       带 LIMIT 的 DELETE 语句（含一个 ? 占位符）
     * @param param     WHERE 条件参数
     * @return 本次任务累计删除行数
     */
    private int deleteBatch(String tableName, String sql, Object param) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbcTemplate.update(sql, param);
            total += deleted;
            if (deleted > 0) {
                log.debug("[ZipkinCleanup] {} 本批删除 {} 行，累计 {}", tableName, deleted, total);
            }
        } while (deleted >= batchSize);   // 删满一批说明可能还有更多，继续
        return total;
    }
}
