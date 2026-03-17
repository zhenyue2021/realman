package org.jeecg.modules.device.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
    private final IMasterOperationRecordService operationRecordService;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeviceWebSocketServer webSocketServer;
    private final ObjectMapper objectMapper;

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
            int seconds = parseHmsSeconds(cfg.getTimeoutAlertOffset(), 1800);
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
            if (cfg == null || cfg.getTaskLimitEnabled() == null || cfg.getTaskLimitEnabled() != 1) {
                continue;
            }
            o.setStatus("TIMEOUT");
            workOrderMapper.updateById(o);
            if (o.getPlanEndTime() != null) {
                operationRecordService.finishByWorkOrder(o.getId(), o.getPlanEndTime());
            }
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
            int seconds = parseHmsSeconds(cfg.getAutoCloseOffset(), 0);
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

    /**
     * 到达工单开始时间时，推送该主控下的工单到前端（WebSocket）
     *
     * <p>XXL-Job Handler Name：workOrderStartPushJob
     * 建议 Cron：0 0/1 * * * ? （每分钟）
     *
     * <p>去重：每个工单仅推送一次，使用 Redis Key 标记。
     */
    @XxlJob("workOrderStartPushJob")
    public void startTimePush() {
        LocalDateTime now = LocalDateTime.now();
        // 只扫描最近24小时内已到开始时间且仍未开始的工单，避免全表扫描
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getStatus, "PENDING")
                        .isNotNull(WorkOrder::getPlanStartTime)
                        .le(WorkOrder::getPlanStartTime, now)
                        .ge(WorkOrder::getPlanStartTime, now.minusHours(24))
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, String> controllerCodeByOrderId = loadControllerDeviceCodes(candidates);
        if (controllerCodeByOrderId.isEmpty()) {
            return;
        }

        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        int pushed = 0;
        for (WorkOrder o : candidates) {
            String controllerCode = controllerCodeByOrderId.get(o.getId());
            if (controllerCode == null || controllerCode.isBlank()) {
                continue;
            }
            String key = "work-order:start-pushed:" + o.getId();
            Boolean first = ops.setIfAbsent(key, "1", Duration.ofHours(48));
            if (!Boolean.TRUE.equals(first)) {
                continue;
            }
            try {
                String json = objectMapper.writeValueAsString(o);
                webSocketServer.pushWorkOrderStart(controllerCode, json);
                pushed++;
                log.info("[WorkOrder-StartPush] pushed orderId={} controllerCode={} planStartTime={}",
                        o.getId(), controllerCode, o.getPlanStartTime());
            } catch (JsonProcessingException e) {
                // 序列化失败：允许下次重试（删除去重 Key）
                redisTemplate.delete(key);
                log.warn("[WorkOrder-StartPush] serialize failed, orderId={}", o.getId(), e);
            } catch (Exception e) {
                redisTemplate.delete(key);
                log.warn("[WorkOrder-StartPush] push failed, orderId={} controllerCode={}", o.getId(), controllerCode, e);
            }
        }
        if (pushed > 0) {
            log.info("[WorkOrder-StartPush] 本次推送工单 {} 条", pushed);
        }
    }

    private Map<String, String> loadControllerDeviceCodes(List<WorkOrder> orders) {
        List<String> orderIds = orders.stream()
                .map(WorkOrder::getId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<WorkOrderDevice> devices = workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .in(WorkOrderDevice::getWorkOrderId, orderIds)
                        .eq(WorkOrderDevice::getDeviceType, "CONTROLLER")
        );
        if (devices == null || devices.isEmpty()) {
            return Map.of();
        }
        return devices.stream()
                .filter(d -> d.getWorkOrderId() != null && !d.getWorkOrderId().isBlank())
                .collect(Collectors.toMap(
                        WorkOrderDevice::getWorkOrderId,
                        d -> (d.getActualDeviceCode() != null && !d.getActualDeviceCode().isBlank())
                                ? d.getActualDeviceCode()
                                : d.getDeviceCode(),
                        (a, b) -> a
                ));
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

    private static int parseHmsSeconds(String hms, int defaultSeconds) {
        if (hms == null || hms.isBlank()) {
            return defaultSeconds;
        }
        String[] parts = hms.trim().split(":");
        if (parts.length != 3) {
            return defaultSeconds;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int s = Integer.parseInt(parts[2].trim());
            if (h < 0 || m < 0 || s < 0) {
                return defaultSeconds;
            }
            return h * 3600 + m * 60 + s;
        } catch (Exception e) {
            return defaultSeconds;
        }
    }
}

