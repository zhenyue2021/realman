package org.jeecg.modules.device.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工单相关定时任务：
 * 1) 超时提醒
 * 2) 超时标记
 * 3) 超时未提交自动关闭
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkOrderSchedulerJob {

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderComplianceConfigMapper configMapper;

    /**
     * 超时提醒任务
     *
     * <p>XXL-Job Handler Name：workOrderTimeoutAlertJob
     * 建议 Cron：0 0/1 * * * ? （每分钟）
     */
    @XxlJob("workOrderTimeoutAlertJob")
    public void timeoutAlert() {
        LocalDateTime now = LocalDateTime.now();
        // 只筛选即将结束的一小段时间窗口，避免全表扫描，这里简单取未来1小时内结束的工单
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .in(WorkOrder::getStatus, "PENDING", "STARTED")
                        .isNotNull(WorkOrder::getPlanEndTime)
                        .ge(WorkOrder::getPlanEndTime, now)
                        .le(WorkOrder::getPlanEndTime, now.plusHours(1))
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, WorkOrderComplianceConfig> configMap = loadConfigs(candidates);
        int alerted = 0;
        for (WorkOrder o : candidates) {
            WorkOrderComplianceConfig cfg = configMap.get(o.getComplianceId());
            if (cfg == null || cfg.getTimeoutAlertEnabled() == null || cfg.getTimeoutAlertEnabled() != 1) {
                continue;
            }
            int seconds = cfg.getTimeoutAlertSeconds() != null ? cfg.getTimeoutAlertSeconds() : 1800;
            long diff = Duration.between(now, o.getPlanEndTime()).getSeconds();
            if (diff <= 0 || diff > seconds) {
                continue;
            }
            alerted++;
            // 这里仅记录日志，实际环境可接入消息推送至主控端
            log.info("[WorkOrder-TimeoutAlert] 工单即将超时, id={}, endTime={}, remainSeconds={}",
                    o.getId(), o.getPlanEndTime(), diff);
        }
        if (alerted > 0) {
            log.info("[WorkOrder-TimeoutAlert] 本次共提醒工单 {} 条", alerted);
        }
    }

    /**
     * 超时标记任务：将已过期且未提交的工单标记为 TIMEOUT
     *
     * <p>XXL-Job Handler Name：workOrderTimeoutMarkJob
     * 建议 Cron：0 0/1 * * * ? （每分钟）
     */
    @XxlJob("workOrderTimeoutMarkJob")
    public void timeoutMark() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .in(WorkOrder::getStatus, "PENDING", "STARTED")
                        .isNotNull(WorkOrder::getPlanEndTime)
                        .le(WorkOrder::getPlanEndTime, now)
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, WorkOrderComplianceConfig> configMap = loadConfigs(candidates);
        int updated = 0;
        for (WorkOrder o : candidates) {
            WorkOrderComplianceConfig cfg = configMap.get(o.getComplianceId());
            if (cfg == null || cfg.getSubmitLimitEnabled() == null || cfg.getSubmitLimitEnabled() != 1) {
                continue;
            }
            o.setStatus("TIMEOUT");
            workOrderMapper.updateById(o);
            updated++;
        }
        if (updated > 0) {
            log.warn("[WorkOrder-TimeoutMark] 本次标记超时工单 {} 条", updated);
        }
    }

    /**
     * 超时未提交自动关闭任务
     *
     * <p>XXL-Job Handler Name：workOrderAutoCloseJob
     * 建议 Cron：0 0/1 * * * ? （每分钟）
     */
    @XxlJob("workOrderAutoCloseJob")
    public void autoClose() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getStatus, "TIMEOUT")
                        .isNotNull(WorkOrder::getPlanEndTime)
                        .isNull(WorkOrder::getTimeoutReason)
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, WorkOrderComplianceConfig> configMap = loadConfigs(candidates);
        int closed = 0;
        for (WorkOrder o : candidates) {
            WorkOrderComplianceConfig cfg = configMap.get(o.getComplianceId());
            if (cfg == null || cfg.getAutoCloseEnabled() == null || cfg.getAutoCloseEnabled() != 1) {
                continue;
            }
            int seconds = cfg.getAutoCloseSeconds() != null ? cfg.getAutoCloseSeconds() : 0;
            if (seconds <= 0) {
                continue;
            }
            LocalDateTime threshold = o.getPlanEndTime().plusSeconds(seconds);
            if (now.isBefore(threshold)) {
                continue;
            }
            o.setStatus("CLOSED");
            o.setTimeoutReasonSource("SYSTEM");
            o.setTimeoutReason("用户原因");
            o.setCloseTime(now);
            o.setCloseBy("SYSTEM");
            workOrderMapper.updateById(o);
            closed++;
        }
        if (closed > 0) {
            log.warn("[WorkOrder-AutoClose] 本次自动关闭超时未填原因工单 {} 条", closed);
        }
    }

    private Map<String, WorkOrderComplianceConfig> loadConfigs(List<WorkOrder> orders) {
        List<String> ids = orders.stream()
                .map(WorkOrder::getComplianceId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<WorkOrderComplianceConfig> cfgs = configMapper.selectList(
                new LambdaQueryWrapper<WorkOrderComplianceConfig>()
                        .in(WorkOrderComplianceConfig::getId, ids)
                        .eq(WorkOrderComplianceConfig::getDelFlag, 0)
        );
        return cfgs.stream().collect(Collectors.toMap(WorkOrderComplianceConfig::getId, c -> c));
    }
}

