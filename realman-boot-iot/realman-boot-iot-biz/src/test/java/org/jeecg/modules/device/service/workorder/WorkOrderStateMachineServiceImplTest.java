package org.jeecg.modules.device.service.workorder;

import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.service.impl.workorder.WorkOrderStateMachineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 工单状态机单元测试（状态流转、操作记录联动）
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderStateMachineServiceImplTest {

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderDeviceMapper workOrderDeviceMapper;
    @Mock
    private IMasterOperationRecordService operationRecordService;

    @InjectMocks
    private WorkOrderStateMachineServiceImpl stateMachine;

    private WorkOrder order;
    private static final LocalDateTime PLAN_END = LocalDateTime.of(2026, 2, 5, 18, 0);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(stateMachine, "baseMapper", workOrderMapper);
        order = new WorkOrder();
        order.setId("wo-001");
        order.setStatus("PENDING");
        order.setPlanStartTime(LocalDateTime.of(2026, 2, 5, 9, 0));
        order.setPlanEndTime(PLAN_END);
        order.setDelFlag(0);
    }

    @Test
    @DisplayName("startWorkOrder：非 PENDING 状态抛异常")
    void startWorkOrder_throwsWhenNotPending() {
        order.setStatus("STARTED");
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);

        assertThatThrownBy(() ->
                stateMachine.startWorkOrder("wo-001", "op1", "张三", "13800138000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许开始");
        verify(workOrderMapper).selectById("wo-001");
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
        verifyNoInteractions(operationRecordService);
    }

    @Test
    @DisplayName("startWorkOrder：未到计划开始时间抛异常")
    void startWorkOrder_throwsWhenBeforePlanStart() {
        order.setPlanStartTime(LocalDateTime.now().plusHours(1));
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);

        assertThatThrownBy(() ->
                stateMachine.startWorkOrder("wo-001", "op1", "张三", "13800138000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未到工单计划开始时间");
        verifyNoInteractions(operationRecordService);
    }

    @Test
    @DisplayName("startWorkOrder：工单不存在则静默返回")
    void startWorkOrder_noOpWhenOrderNull() {
        when(workOrderMapper.selectById("wo-001")).thenReturn(null);

        stateMachine.startWorkOrder("wo-001", "op1", "张三", "138");

        verify(workOrderMapper).selectById("wo-001");
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
        verifyNoInteractions(operationRecordService);
    }

    @Test
    @DisplayName("startWorkOrder：时间窗口内 PENDING 时更新状态并创建操作记录")
    void startWorkOrder_success_updatesAndCreatesRecords() {
        order.setPlanStartTime(LocalDateTime.now().minusHours(1));
        order.setPlanEndTime(LocalDateTime.now().plusHours(1));
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        stateMachine.startWorkOrder("wo-001", "op1", "张三", "13800138000");

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("STARTED");
        assertThat(captor.getValue().getOperatorId()).isEqualTo("op1");
        assertThat(captor.getValue().getOperatorName()).isEqualTo("张三");
        assertThat(captor.getValue().getActualStartTime()).isNotNull();
        verify(operationRecordService).createRecordsForWorkOrderStart(
                eq("wo-001"), eq("op1"), eq("张三"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("submitWorkOrder：非 STARTED/TIMEOUT 状态抛异常")
    void submitWorkOrder_throwsWhenInvalidStatus() {
        order.setStatus("PENDING");
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);

        assertThatThrownBy(() -> stateMachine.submitWorkOrder("wo-001", "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许提交");
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
        verifyNoInteractions(operationRecordService);
    }

    @Test
    @DisplayName("submitWorkOrder：STARTED 时更新状态并调用操作记录结束")
    void submitWorkOrder_updatesAndFinishesRecords() {
        order.setStatus("STARTED");
        order.setOperatorName("operator");
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        stateMachine.submitWorkOrder("wo-001", "operator");

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUBMITTED");
        assertThat(captor.getValue().getSubmitTime()).isNotNull();
        verify(operationRecordService).finishByWorkOrder(eq("wo-001"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("fillTimeoutReason：未传 source 时默认为「用户原因」")
    void fillTimeoutReason_defaultSourceUser() {
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        stateMachine.fillTimeoutReason("wo-001", "设备故障", null);

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTimeoutReason()).isEqualTo("设备故障");
        assertThat(captor.getValue().getTimeoutReasonSource()).isEqualTo("用户原因");
    }

    @Test
    @DisplayName("auditWorkOrder：仅 SUBMITTED/TIMEOUT 可审核")
    void auditWorkOrder_throwsWhenInvalidStatus() {
        order.setStatus("PENDING");
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);

        assertThatThrownBy(() -> stateMachine.auditWorkOrder("wo-001", "PASS", "合格", "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许审核");
    }

    @Test
    @DisplayName("auditWorkOrder：SUBMITTED 时更新为 COMPLETED")
    void auditWorkOrder_updatesToCompleted() {
        order.setStatus("SUBMITTED");
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        stateMachine.auditWorkOrder("wo-001", "PASS", "合格", "admin");

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().getAuditResult()).isEqualTo("PASS");
        assertThat(captor.getValue().getAuditBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("closeWorkOrder：TIMEOUT 且无原因时补齐 SYSTEM/用户原因")
    void closeWorkOrder_fillsDefaultReasonWhenTimeout() {
        order.setStatus("TIMEOUT");
        order.setTimeoutReason(null);
        order.setPlanEndTime(PLAN_END);
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);
        when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);

        stateMachine.closeWorkOrder("wo-001", null, "admin");

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(captor.getValue().getTimeoutReasonSource()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().getTimeoutReason()).isEqualTo("用户原因");
        verify(operationRecordService).finishByWorkOrder("wo-001", PLAN_END);
    }
}
