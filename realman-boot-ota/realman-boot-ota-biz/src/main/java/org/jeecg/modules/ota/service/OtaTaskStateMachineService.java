package org.jeecg.modules.ota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.ota.contract.enums.OtaErrorCode;
import org.jeecg.modules.ota.contract.enums.OtaTaskState;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaTask;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.enums.NonTerminalStates;
import org.jeecg.modules.ota.mapper.OtaFirmwareMapper;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaTaskMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.DISPATCH_MAX_ATTEMPTS;
import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.DISPATCH_RETRY_INTERVAL_SECONDS;

/**
 * 15 态状态机的共享操作：单设备下发（含下发前二次校验）+ 批量任务聚合状态重算。
 * 被 {@code OtaTaskServiceImpl}（创建/重试/继续）与上行事件消费（进度上报驱动
 * 子任务状态变化后需要重算聚合状态）两处共用，避免逻辑分叉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaTaskStateMachineService {

    /** OSS 预签名 URL 剩余有效期低于此阈值时下发前主动刷新，对齐详细设计 9.9 附录"剩余&lt;1小时自动刷新" */
    private static final long URL_REFRESH_THRESHOLD_MINUTES = 60;

    private final OtaTaskMapper taskMapper;
    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final OtaFirmwareMapper firmwareMapper;
    private final IOtaPrecheckService precheckService;
    private final IOtaDownlinkService downlinkService;
    private final IOtaSystemSettingService systemSettingService;
    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final IOtaFirmwareService firmwareService;

    /**
     * 下发前二次校验（版本兼容性 + 签名吊销，PRD 4.4.2 双重校验的第二次）+ 下发。
     * 二次校验只做这两项，不重复设备状态/资源检查——PRD 4.4.2 表格里"下发设备时
     * （第二次）"这一行只列了版本兼容性与签名吊革两项。校验失败时子任务直接置
     * FAILED，不下发。
     */
    public void dispatchDevice(OtaTaskDevice taskDevice, OtaFirmware firmware) {
        DeviceInfoDTO device = resolveDevice(taskDevice.getDeviceId());
        if (device == null) {
            failDevice(taskDevice, OtaErrorCode.ERR_PRECONDITION_FAILED, "下发前设备信息查询失败");
            return;
        }
        try {
            precheckService.checkVersionCompatibility(device, firmware);
            precheckService.checkSignatureNotRevoked(firmware);
        } catch (JeecgBootBizTipException e) {
            String errorCode = e.getMessage() != null && e.getMessage().contains("ERR_KEY_REVOKED")
                    ? OtaErrorCode.ERR_KEY_REVOKED : OtaErrorCode.ERR_VERSION_INCOMPATIBLE;
            failDevice(taskDevice, errorCode, e.getMessage());
            return;
        }

        taskDevice.setState(OtaTaskState.STARTING.name());
        taskDevice.setStateChangedAt(LocalDateTime.now());
        attemptDispatch(taskDevice, firmware);
    }

    /**
     * 实际执行一次下行发布尝试并落库 dispatch_attempt_count/last_dispatch_attempt_at。
     * 失败且已达 dispatch_max_attempts 时置 FAILED（{@code ERR_PENDING_DISPATCH_TIMEOUT}，
     * 复用为"下发在允许时间窗口内始终未能成功送达"的语义，PRD 30 个错误码未单列
     * "下行发布多次失败"这一独立场景）；未达上限则保持 STARTING，等待
     * {@link #retryPendingDispatches()} 下一轮重试。
     */
    private void attemptDispatch(OtaTaskDevice taskDevice, OtaFirmware firmware) {
        OtaFirmware effectiveFirmware = refreshUrlIfExpiringSoon(firmware);

        int attempts = (taskDevice.getDispatchAttemptCount() == null ? 0 : taskDevice.getDispatchAttemptCount()) + 1;
        taskDevice.setDispatchAttemptCount(attempts);
        taskDevice.setLastDispatchAttemptAt(LocalDateTime.now());
        taskDeviceMapper.updateById(taskDevice);

        boolean dispatched = downlinkService.notifyDevice(taskDevice, effectiveFirmware);
        if (dispatched) {
            return;
        }
        long maxAttempts = systemSettingService.getLong(DISPATCH_MAX_ATTEMPTS);
        if (attempts >= maxAttempts) {
            log.warn("[ota] 下行通知连续 {} 次失败，已达上限，置 FAILED taskId={} deviceCode={}",
                    attempts, taskDevice.getTaskId(), taskDevice.getDeviceCode());
            failDevice(taskDevice, OtaErrorCode.ERR_PENDING_DISPATCH_TIMEOUT,
                    "下行通知连续 " + attempts + " 次失败，超过 dispatch_max_attempts=" + maxAttempts);
        } else {
            log.warn("[ota] 下行通知失败，等待下一轮自动重试（第 {}/{} 次）taskId={} deviceCode={}",
                    attempts, maxAttempts, taskDevice.getTaskId(), taskDevice.getDeviceCode());
        }
    }

    /**
     * 下发前若固件为 OSS 存储且预签名 URL 剩余有效期不足 {@link #URL_REFRESH_THRESHOLD_MINUTES}
     * 分钟（含已过期），主动刷新后再下发，避免设备拿到即将/已经失效的下载地址。
     * 返回刷新后的固件对象（未刷新时原样返回入参）。
     */
    private OtaFirmware refreshUrlIfExpiringSoon(OtaFirmware firmware) {
        if (!"OSS".equals(firmware.getStorageSource())) {
            return firmware;
        }
        LocalDateTime expiresAt = firmware.getDownloadUrlExpiresAt();
        if (expiresAt != null && expiresAt.isAfter(LocalDateTime.now().plusMinutes(URL_REFRESH_THRESHOLD_MINUTES))) {
            return firmware;
        }
        firmwareService.refreshDownloadUrl(firmware.getPackageId());
        return firmwareMapper.selectById(firmware.getPackageId());
    }

    /**
     * 下发失败自动重试扫描：找出仍处于 STARTING 且距上次尝试已超过
     * dispatch_retry_interval_seconds 的子任务，重新走一次下行发布。由
     * {@code OtaUplinkPollingService} 的定时任务驱动调用。
     */
    public void retryPendingDispatches() {
        long intervalSeconds = systemSettingService.getLong(DISPATCH_RETRY_INTERVAL_SECONDS);
        List<OtaTaskDevice> starting = taskDeviceMapper.selectList(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getState, OtaTaskState.STARTING.name())
                .isNotNull(OtaTaskDevice::getLastDispatchAttemptAt));
        for (OtaTaskDevice taskDevice : starting) {
            long elapsedSeconds = ChronoUnit.SECONDS.between(taskDevice.getLastDispatchAttemptAt(), LocalDateTime.now());
            if (elapsedSeconds < intervalSeconds) {
                continue;
            }
            OtaTask task = taskMapper.selectById(taskDevice.getTaskId());
            if (task == null) {
                continue;
            }
            OtaFirmware firmware = firmwareMapper.selectById(task.getPackageId());
            if (firmware == null) {
                log.warn("[ota] 重试下发时固件包已不存在，跳过 taskId={} packageId={}", task.getTaskId(), task.getPackageId());
                continue;
            }
            attemptDispatch(taskDevice, firmware);
            // attemptDispatch 失败达上限时会把子任务置为 FAILED，需要重算聚合状态，
            // 与 OtaTaskServiceImpl 里每次 dispatchDevice 后都跟一次 recomputeBatchStatus 的约定一致
            recomputeBatchStatus(taskDevice.getTaskId());
        }
    }

    /**
     * ERR_URL_EXPIRED 自愈：设备上报固件下载 URL 已过期时，若该固件为 OSS 存储
     * 且下发尝试次数未达 dispatch_max_attempts，则刷新预签名 URL 并立即重新下发
     * （复用 STARTING 态与 attemptDispatch，与常规下发失败自动重试共用同一预算），
     * 而不是让子任务直接落入 FAILED 终态（详细设计 9.9 附录"剩余&lt;1小时或收到
     * ERR_URL_EXPIRED 时自动刷新"）。返回 true 表示已处理（调用方无需再走常规
     * 状态落库逻辑），false 表示不适用，调用方应按原上报状态正常处理。
     */
    public boolean handleUrlExpiredIfApplicable(OtaTaskDevice taskDevice, String upgradeErrorCode) {
        if (!OtaErrorCode.ERR_URL_EXPIRED.equals(upgradeErrorCode)) {
            return false;
        }
        long maxAttempts = systemSettingService.getLong(DISPATCH_MAX_ATTEMPTS);
        int attempts = taskDevice.getDispatchAttemptCount() == null ? 0 : taskDevice.getDispatchAttemptCount();
        if (attempts >= maxAttempts) {
            log.warn("[ota] ERR_URL_EXPIRED 但下发尝试次数已达上限，按常规 FAILED 处理 taskId={} deviceCode={}",
                    taskDevice.getTaskId(), taskDevice.getDeviceCode());
            return false;
        }
        OtaTask task = taskMapper.selectById(taskDevice.getTaskId());
        if (task == null) {
            return false;
        }
        OtaFirmware firmware = firmwareMapper.selectById(task.getPackageId());
        if (firmware == null || !"OSS".equals(firmware.getStorageSource())) {
            return false;
        }
        firmwareService.refreshDownloadUrl(firmware.getPackageId());
        OtaFirmware refreshed = firmwareMapper.selectById(firmware.getPackageId());
        log.info("[ota] 收到 ERR_URL_EXPIRED，已刷新预签名 URL 并重新下发 taskId={} deviceCode={}",
                taskDevice.getTaskId(), taskDevice.getDeviceCode());
        taskDevice.setUpgradeErrorCode(null);
        taskDevice.setUpgradeErrorMsg(null);
        taskDevice.setState(OtaTaskState.STARTING.name());
        taskDevice.setStateChangedAt(LocalDateTime.now());
        attemptDispatch(taskDevice, refreshed);
        return true;
    }

    private void failDevice(OtaTaskDevice taskDevice, String errorCode, String errorMsg) {
        taskDevice.setState(OtaTaskState.FAILED.name());
        taskDevice.setUpgradeErrorCode(errorCode);
        taskDevice.setUpgradeErrorMsg(errorMsg);
        taskDevice.setStateChangedAt(LocalDateTime.now());
        taskDeviceMapper.updateById(taskDevice);
    }

    private DeviceInfoDTO resolveDevice(String deviceId) {
        try {
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDevice(deviceId);
            return result != null && result.isSuccess() ? result.getResult() : null;
        } catch (Exception e) {
            log.warn("[ota] 下发前查询设备信息失败 deviceId={}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    /**
     * 重算批量任务聚合状态：完成率、stop_all_triggered 清除时机、最终终态判定。
     * 在任意子任务状态变化后调用（创建、重试、progress-push 落库后、abort 后）。
     */
    public void recomputeBatchStatus(String taskId) {
        OtaTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<OtaTaskDevice> subTasks = taskDeviceMapper.selectList(
                Wrappers.<OtaTaskDevice>lambdaQuery().eq(OtaTaskDevice::getTaskId, taskId));
        long total = subTasks.size();
        long completed = countState(subTasks, OtaTaskState.COMPLETED);
        long cancelled = countState(subTasks, OtaTaskState.CANCELLED);
        long failed = countState(subTasks, OtaTaskState.FAILED) + countState(subTasks, OtaTaskState.ROLLBACK_FAILED);
        long nonTerminal = subTasks.stream().filter(d -> !NonTerminalStates.isTerminal(d.getState())).count();

        if (Boolean.TRUE.equals(task.getStopAllTriggered()) && nonTerminal == 0) {
            task.setStopAllTriggered(false);
            finalizeAbortedStatus(task, total, completed, cancelled, failed);
        } else if (nonTerminal == 0 && !"PAUSED".equals(task.getStatus())) {
            if (cancelled > 0 && total - cancelled == 0) {
                task.setStatus("CANCELLED");
            } else if (completed == total) {
                task.setStatus("COMPLETED");
            } else if (failed >= (task.getActiveFailThresholdSnapshot() == null ? Integer.MAX_VALUE : task.getActiveFailThresholdSnapshot())) {
                task.setStatus("FAILED");
            } else if (failed > 0 || cancelled > 0) {
                task.setStatus("PARTIAL_COMPLETED");
            } else {
                task.setStatus("COMPLETED");
            }
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void finalizeAbortedStatus(OtaTask task, long total, long completed, long cancelled, long failed) {
        long executed = total - cancelled;
        if (executed == 0) {
            task.setStatus("CANCELLED");
            return;
        }
        int completionRatePct = (int) Math.ceil(completed * 100.0 / executed);
        int snapshot = task.getActiveFailThresholdSnapshot() == null ? Integer.MAX_VALUE : task.getActiveFailThresholdSnapshot();
        if (completionRatePct >= 100) {
            task.setStatus("COMPLETED");
        } else if (failed >= snapshot) {
            task.setStatus("FAILED");
        } else {
            task.setStatus("PARTIAL_COMPLETED");
        }
    }

    private long countState(List<OtaTaskDevice> subTasks, OtaTaskState state) {
        return subTasks.stream().filter(d -> state.name().equals(d.getState())).count();
    }
}
