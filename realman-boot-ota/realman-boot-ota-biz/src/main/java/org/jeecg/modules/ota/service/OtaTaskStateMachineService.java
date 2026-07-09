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
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaTaskMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 15 态状态机的共享操作：单设备下发（含下发前二次校验）+ 批量任务聚合状态重算。
 * 被 {@code OtaTaskServiceImpl}（创建/重试/继续）与上行事件消费（进度上报驱动
 * 子任务状态变化后需要重算聚合状态）两处共用，避免逻辑分叉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaTaskStateMachineService {

    private final OtaTaskMapper taskMapper;
    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final IOtaPrecheckService precheckService;
    private final IOtaDownlinkService downlinkService;
    private final DeviceInfoFeignClient deviceInfoFeignClient;

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
        taskDeviceMapper.updateById(taskDevice);

        boolean dispatched = downlinkService.notifyDevice(taskDevice, firmware);
        if (!dispatched) {
            log.warn("[ota] 下行通知失败，子任务保持 STARTING 等待后续人工重试（本轮未实现自动重试扫描） taskId={} deviceCode={}",
                    taskDevice.getTaskId(), taskDevice.getDeviceCode());
        }
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
