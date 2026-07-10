package org.jeecg.modules.ota.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.api.CommHubFeignClient;
import org.jeecg.modules.commhub.contract.dto.UplinkEventPollQuery;
import org.jeecg.modules.commhub.contract.event.DeviceUplinkEvent;
import org.jeecg.modules.ota.contract.enums.OtaTaskState;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.entity.OtaUplinkPollCursor;
import org.jeecg.modules.ota.enums.NonTerminalStates;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaUplinkPollCursorMapper;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.service.OtaTaskStateMachineService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.CANCEL_ACK_TIMEOUT_SECONDS;

/**
 * 上行事件消费：定时轮询通信中台归一化后的 {@code OTA_PROGRESS}/
 * {@code OTA_STATUS_REPORT} 事件，驱动设备级子任务状态机迁移；同时兜底扫描
 * EXECUTING 取消确认超时（PRD 4.6.1 cancel_ack_timeout）+ 下发失败自动重试。
 *
 * <p>轮询游标按 {@code eventKind} 各自持久化到 {@code ota_uplink_poll_cursor}
 * （修复此前两个 eventKind 共用同一个内存游标的问题——共用游标会导致后轮询的
 * eventKind 的 {@code since} 被先轮询的 eventKind 已推进的游标误覆盖，从而
 * 漏掉本应处理的事件），服务重启/多实例部署下游标不丢失、不互相干扰；内存里
 * 仅做读多写少场景下的缓存加速，最终以 DB 值为准（只前进不回退）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaUplinkPollingService {

    private static final String EVENT_KIND_OTA_PROGRESS = "OTA_PROGRESS";
    private static final String EVENT_KIND_OTA_STATUS_REPORT = "OTA_STATUS_REPORT";

    private final CommHubFeignClient commHubFeignClient;
    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final OtaUplinkPollCursorMapper cursorMapper;
    private final OtaTaskStateMachineService stateMachineService;
    private final IOtaSystemSettingService systemSettingService;

    private final Map<String, LocalDateTime> cursorCache = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${ota.uplink-poll.interval-ms:5000}")
    public void poll() {
        try {
            pollEventKind(EVENT_KIND_OTA_PROGRESS);
            pollEventKind(EVENT_KIND_OTA_STATUS_REPORT);
        } catch (Exception e) {
            log.warn("[ota] 上行事件轮询异常: {}", e.getMessage());
        }
        try {
            checkCancelAckTimeouts();
        } catch (Exception e) {
            log.warn("[ota] 取消确认超时扫描异常: {}", e.getMessage());
        }
        try {
            stateMachineService.retryPendingDispatches();
        } catch (Exception e) {
            log.warn("[ota] 下发失败自动重试扫描异常: {}", e.getMessage());
        }
    }

    private void pollEventKind(String eventKind) {
        LocalDateTime since = loadCursor(eventKind);
        UplinkEventPollQuery query = new UplinkEventPollQuery();
        query.setEventKind(eventKind);
        query.setSince(since);
        query.setLimit(200);
        Result<List<DeviceUplinkEvent>> result = commHubFeignClient.pollUplinkEvents(query);
        if (result == null || !result.isSuccess() || result.getResult() == null || result.getResult().isEmpty()) {
            return;
        }
        LocalDateTime maxReportedAt = since;
        for (DeviceUplinkEvent event : result.getResult()) {
            try {
                applyEvent(event);
            } catch (Exception e) {
                log.warn("[ota] 上行事件应用失败 deviceCode={}: {}", event.getDeviceCode(), e.getMessage());
            }
            if (event.getReportedAt() != null && event.getReportedAt().isAfter(maxReportedAt)) {
                maxReportedAt = event.getReportedAt();
            }
        }
        if (maxReportedAt.isAfter(since)) {
            saveCursor(eventKind, maxReportedAt);
        }
    }

    /** 优先取内存缓存；缓存未命中（服务刚启动）时从 DB 加载，仍缺失则回退到"启动前 10 分钟"。 */
    private LocalDateTime loadCursor(String eventKind) {
        LocalDateTime cached = cursorCache.get(eventKind);
        if (cached != null) {
            return cached;
        }
        OtaUplinkPollCursor persisted = cursorMapper.selectById(eventKind);
        LocalDateTime initial = persisted != null && persisted.getCursorAt() != null
                ? persisted.getCursorAt() : LocalDateTime.now().minusMinutes(10);
        cursorCache.put(eventKind, initial);
        return initial;
    }

    /** 原子的"只前进不回退"落库，避免多实例并发轮询时较慢的一个实例把游标写回退。 */
    private void saveCursor(String eventKind, LocalDateTime newCursor) {
        cursorCache.put(eventKind, newCursor);
        try {
            cursorMapper.upsertIfAfter(eventKind, newCursor);
        } catch (Exception e) {
            log.warn("[ota] 轮询游标持久化失败 eventKind={}: {}", eventKind, e.getMessage());
        }
    }

    private void applyEvent(DeviceUplinkEvent event) {
        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            return;
        }
        String taskId = stringField(payload, "taskId");
        if (taskId == null) {
            log.debug("[ota] 上行事件缺少 taskId，忽略 deviceCode={}", event.getDeviceCode());
            return;
        }
        OtaTaskDevice subTask = taskDeviceMapper.selectOne(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getTaskId, taskId)
                .eq(OtaTaskDevice::getDeviceId, event.getDeviceId())
                .last("LIMIT 1"));
        if (subTask == null) {
            log.debug("[ota] 未找到对应子任务，忽略 taskId={} deviceCode={}", taskId, event.getDeviceCode());
            return;
        }
        if (NonTerminalStates.isTerminal(subTask.getState())) {
            log.debug("[ota] 子任务已处于终态，忽略重复/延迟上报 taskId={} state={}", taskId, subTask.getState());
            return;
        }

        Object symlinkSwitched = payload.get("symlink_switched");
        if (symlinkSwitched != null && subTask.getCancelRequestedAt() != null) {
            applyCancelAck(subTask, isTrue(symlinkSwitched));
            return;
        }

        String status = stringField(payload, "status");
        if (status == null) {
            return;
        }
        OtaTaskState newState;
        try {
            newState = OtaTaskState.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[ota] 未识别的上报状态，忽略 taskId={} status={}", taskId, status);
            return;
        }

        String upgradeErrorCode = stringField(payload, "upgrade_error_code");
        if (newState == OtaTaskState.FAILED
                && stateMachineService.handleUrlExpiredIfApplicable(subTask, upgradeErrorCode)) {
            stateMachineService.recomputeBatchStatus(taskId);
            return;
        }

        subTask.setState(newState.name());
        Object progressPct = payload.get("progress_pct");
        if (progressPct instanceof Number number) {
            subTask.setProgressPct(number.intValue());
        }
        subTask.setSubStage(stringField(payload, "sub_stage"));
        subTask.setSigVerifyResult(stringField(payload, "sig_verify_result"));
        subTask.setUpgradeErrorCode(upgradeErrorCode);
        subTask.setUpgradeErrorMsg(stringField(payload, "upgrade_error_msg"));
        subTask.setReportedAt(event.getReportedAt());
        subTask.setStateChangedAt(LocalDateTime.now());
        taskDeviceMapper.updateById(subTask);
        stateMachineService.recomputeBatchStatus(taskId);
    }

    private void applyCancelAck(OtaTaskDevice subTask, boolean symlinkSwitched) {
        subTask.setState((symlinkSwitched ? OtaTaskState.ROLLING_BACK : OtaTaskState.CANCELLED).name());
        subTask.setCancelRequestedAt(null);
        subTask.setStateChangedAt(LocalDateTime.now());
        taskDeviceMapper.updateById(subTask);
        stateMachineService.recomputeBatchStatus(subTask.getTaskId());
    }

    /** cancel_ack_timeout 兜底：超时未收到 symlink_switched，按保守原则视为已切换，触发回滚。 */
    private void checkCancelAckTimeouts() {
        long timeoutSeconds = systemSettingService.getLong(CANCEL_ACK_TIMEOUT_SECONDS);
        List<OtaTaskDevice> waitingForAck = taskDeviceMapper.selectList(
                Wrappers.<OtaTaskDevice>lambdaQuery().isNotNull(OtaTaskDevice::getCancelRequestedAt));
        for (OtaTaskDevice subTask : waitingForAck) {
            long elapsedSeconds = ChronoUnit.SECONDS.between(subTask.getCancelRequestedAt(), LocalDateTime.now());
            if (elapsedSeconds > timeoutSeconds) {
                log.warn("[ota] 取消确认超时（{}秒），按保守原则视为 symlink_switched=true，触发回滚 taskId={} deviceCode={}",
                        elapsedSeconds, subTask.getTaskId(), subTask.getDeviceCode());
                applyCancelAck(subTask, true);
            }
        }
    }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String stringField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }
}
