package org.jeecg.modules.device.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.IControllerOperationRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 工单定时任务单元测试：超时提醒、超时标记、自动关闭
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderSchedulerJobTest {

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderComplianceConfigMapper configMapper;
    @Mock
    private IControllerOperationRecordService operationRecordService;

    @InjectMocks
    private WorkOrderSchedulerJob schedulerJob;

    private WorkOrder order;
    private WorkOrderComplianceConfig config;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId("wo-001");
        order.setComplianceId("cfg-001");
        order.setStatus("STARTED");
        order.setPlanEndTime(LocalDateTime.now().minusMinutes(5));
        order.setDelFlag(0);

        config = new WorkOrderComplianceConfig();
        config.setId("cfg-001");
        config.setSubmitLimitEnabled(1);
        config.setAutoCloseEnabled(1);
        config.setAutoCloseSeconds(3600);
    }

    @Test
    @DisplayName("timeoutMark：无候选工单时不更新")
    void timeoutMark_noCandidates_noUpdate() {
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        schedulerJob.timeoutMark();

        verify(workOrderMapper).selectList(any(LambdaQueryWrapper.class));
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
        verifyNoInteractions(operationRecordService);
    }

    @Test
    @DisplayName("timeoutMark：合规未开启提交时限时不标记")
    void timeoutMark_skipWhenSubmitLimitDisabled() {
        config.setSubmitLimitEnabled(0);
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));
        when(configMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(config));

        schedulerJob.timeoutMark();

        verify(workOrderMapper).selectList(any(LambdaQueryWrapper.class));
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    @DisplayName("timeoutMark：合规开启提交时限时标记 TIMEOUT 并结束操作记录")
    void timeoutMark_marksTimeoutAndFinishesRecords() {
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));
        when(configMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(config));
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        schedulerJob.timeoutMark();

        ArgumentCaptor<WorkOrder> orderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo("TIMEOUT");
        verify(operationRecordService).finishByWorkOrder(eq("wo-001"), eq(order.getPlanEndTime()));
    }

    @Test
    @DisplayName("autoClose：无候选不更新")
    void autoClose_noCandidates_noUpdate() {
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        schedulerJob.autoClose();

        verify(workOrderMapper).selectList(any(LambdaQueryWrapper.class));
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    @DisplayName("autoClose：超过阈值且无原因时关闭并填默认原因")
    void autoClose_closesAndSetsDefaultReason() {
        order.setTimeoutReason(null);
        order.setPlanEndTime(LocalDateTime.now().minusSeconds(4000));
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));
        when(configMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(config));
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        schedulerJob.autoClose();

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(captor.getValue().getTimeoutReasonSource()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().getTimeoutReason()).isEqualTo("用户原因");
    }

    @Test
    @DisplayName("timeoutAlert：有即将超时且开启提醒的工单时不抛错")
    void timeoutAlert_doesNotThrow() {
        order.setPlanEndTime(LocalDateTime.now().plusMinutes(10));
        config.setTimeoutAlertEnabled(1);
        config.setTimeoutAlertSeconds(1800);
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));
        when(configMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(config));

        schedulerJob.timeoutAlert();

        verify(workOrderMapper).selectList(any(LambdaQueryWrapper.class));
        verify(configMapper).selectList(any(LambdaQueryWrapper.class));
    }
}
