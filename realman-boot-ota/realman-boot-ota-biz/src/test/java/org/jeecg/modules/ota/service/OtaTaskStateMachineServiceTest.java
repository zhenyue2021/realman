package org.jeecg.modules.ota.service;

import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.ota.contract.enums.OtaTaskState;
import org.jeecg.modules.ota.entity.OtaTask;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.mapper.OtaFirmwareMapper;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量任务聚合状态重算是 15 态状态机对外可见的"任务级"结论，覆盖 PRD 4.4.3
 * 的几种聚合结果分支：全部成功/失败超阈值/部分失败未超阈值/全部取消/仍有
 * 非终态子任务时不应过早判定聚合结果。
 */
@ExtendWith(MockitoExtension.class)
class OtaTaskStateMachineServiceTest {

    private static final String TASK_ID = "task-1";

    @Mock
    private OtaTaskMapper taskMapper;
    @Mock
    private OtaTaskDeviceMapper taskDeviceMapper;
    @Mock
    private OtaFirmwareMapper firmwareMapper;
    @Mock
    private IOtaPrecheckService precheckService;
    @Mock
    private IOtaDownlinkService downlinkService;
    @Mock
    private IOtaSystemSettingService systemSettingService;
    @Mock
    private DeviceInfoFeignClient deviceInfoFeignClient;
    @Mock
    private IOtaFirmwareService firmwareService;

    private OtaTaskStateMachineService stateMachineService;

    @BeforeEach
    void setUp() {
        stateMachineService = new OtaTaskStateMachineService(taskMapper, taskDeviceMapper, firmwareMapper,
                precheckService, downlinkService, systemSettingService, deviceInfoFeignClient, firmwareService);
    }

    private OtaTask task(int failThresholdSnapshot) {
        OtaTask task = new OtaTask();
        task.setTaskId(TASK_ID);
        task.setStatus("IN_PROGRESS");
        task.setStopAllTriggered(false);
        task.setActiveFailThresholdSnapshot(failThresholdSnapshot);
        return task;
    }

    private OtaTaskDevice subTask(String state) {
        OtaTaskDevice device = new OtaTaskDevice();
        device.setTaskId(TASK_ID);
        device.setState(state);
        return device;
    }

    private OtaTask recompute(int failThresholdSnapshot, List<OtaTaskDevice> subTasks) {
        OtaTask task = task(failThresholdSnapshot);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskDeviceMapper.selectList(any())).thenReturn(subTasks);

        stateMachineService.recomputeBatchStatus(TASK_ID);

        ArgumentCaptor<OtaTask> captor = ArgumentCaptor.forClass(OtaTask.class);
        verify(taskMapper).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void recomputeBatchStatus_allCompleted_setsCompleted() {
        OtaTask result = recompute(5, List.of(
                subTask(OtaTaskState.COMPLETED.name()),
                subTask(OtaTaskState.COMPLETED.name())));

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void recomputeBatchStatus_failuresAtOrAboveThreshold_setsFailed() {
        OtaTask result = recompute(2, List.of(
                subTask(OtaTaskState.FAILED.name()),
                subTask(OtaTaskState.FAILED.name()),
                subTask(OtaTaskState.COMPLETED.name())));

        assertThat(result.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void recomputeBatchStatus_someFailedBelowThreshold_setsPartialCompleted() {
        OtaTask result = recompute(5, List.of(
                subTask(OtaTaskState.FAILED.name()),
                subTask(OtaTaskState.COMPLETED.name()),
                subTask(OtaTaskState.COMPLETED.name())));

        assertThat(result.getStatus()).isEqualTo("PARTIAL_COMPLETED");
    }

    @Test
    void recomputeBatchStatus_allCancelled_setsCancelled() {
        OtaTask result = recompute(5, List.of(
                subTask(OtaTaskState.CANCELLED.name()),
                subTask(OtaTaskState.CANCELLED.name())));

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void recomputeBatchStatus_nonTerminalSubTaskRemaining_doesNotFinalizeStatus() {
        OtaTask result = recompute(5, List.of(
                subTask(OtaTaskState.DOWNLOADING.name()),
                subTask(OtaTaskState.COMPLETED.name())));

        // 仍有子任务在执行中，聚合状态不应被判定为任何终态，保持调用前的 IN_PROGRESS
        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
    }
}
