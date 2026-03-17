package org.jeecg.modules.device.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private IMasterOperationRecordService operationRecordService;
    @Mock
    private WorkOrderDeviceMapper workOrderDeviceMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private DeviceWebSocketServer webSocketServer;
    @Mock
    private ObjectMapper objectMapper;

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
        config.setTaskLimitEnabled(1);
        config.setAutoCloseEnabled(1);
        config.setAutoCloseOffset("01:00:00");
    }

    @Test
    @DisplayName("startTimePush：到开始时间且首次推送时发送 WS 消息")
    void startTimePush_pushesOnce() throws Exception {
        WorkOrder pending = new WorkOrder();
        pending.setId("wo-001");
        pending.setStatus("PENDING");
        pending.setPlanStartTime(LocalDateTime.now().minusMinutes(1));
        pending.setDelFlag(0);

        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));

        WorkOrderDevice controller = new WorkOrderDevice();
        controller.setWorkOrderId("wo-001");
        controller.setDeviceType("CONTROLLER");
        controller.setDeviceCode("MASTER001");
        when(workOrderDeviceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(controller));

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq("work-order:start-pushed:wo-001"), eq("1"), any(Duration.class))).thenReturn(true);

        when(objectMapper.writeValueAsString(any(WorkOrder.class))).thenReturn("{\"id\":\"wo-001\"}");

        schedulerJob.startTimePush();

        verify(webSocketServer).pushWorkOrderStart(eq("MASTER001"), eq("{\"id\":\"wo-001\"}"));
    }

    @Test
    @DisplayName("startTimePush：Redis 已标记则不重复推送")
    void startTimePush_skipWhenAlreadyPushed() throws Exception {
        WorkOrder pending = new WorkOrder();
        pending.setId("wo-001");
        pending.setStatus("PENDING");
        pending.setPlanStartTime(LocalDateTime.now().minusMinutes(1));
        pending.setDelFlag(0);

        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));

        WorkOrderDevice controller = new WorkOrderDevice();
        controller.setWorkOrderId("wo-001");
        controller.setDeviceType("CONTROLLER");
        controller.setDeviceCode("MASTER001");
        when(workOrderDeviceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(controller));

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq("work-order:start-pushed:wo-001"), eq("1"), any(Duration.class))).thenReturn(false);

        schedulerJob.startTimePush();

        verifyNoInteractions(webSocketServer);
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
        config.setTaskLimitEnabled(0);
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
        config.setTimeoutAlertOffset("00:30:00");
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));
        when(configMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(config));

        schedulerJob.timeoutAlert();

        verify(workOrderMapper).selectList(any(LambdaQueryWrapper.class));
        verify(configMapper).selectList(any(LambdaQueryWrapper.class));
    }
}
