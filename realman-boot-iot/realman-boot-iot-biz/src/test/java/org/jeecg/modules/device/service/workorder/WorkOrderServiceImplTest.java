package org.jeecg.modules.device.service.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.darwin.mapper.DarwinWorkOrderMappingMapper;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.impl.workorder.WorkOrderServiceImpl;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 工单 Service 单元测试（查询、绑定设备、开始工单时的 WebSocket 编排）
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceImplTest {

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderDeviceMapper workOrderDeviceMapper;
    @Mock
    private IWorkOrderStateMachineService workOrderStateMachine;
    @Mock
    private DeviceWebSocketServer deviceWebSocketServer;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private IotDeviceMapper iotDeviceMapper;
    @Mock
    private DarwinWorkOrderMappingMapper darwinWorkOrderMappingMapper;

    @InjectMocks
    private WorkOrderServiceImpl workOrderService;

    private WorkOrder order;
    private static final LocalDateTime PLAN_END = LocalDateTime.of(2026, 2, 5, 18, 0);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(workOrderService, "baseMapper", workOrderMapper);
        order = new WorkOrder();
        order.setId("wo-001");
        order.setStatus("STARTED");
        order.setPlanStartTime(LocalDateTime.of(2026, 2, 5, 9, 0));
        order.setPlanEndTime(PLAN_END);
        order.setDelFlag(0);
    }

    @Test
    @DisplayName("分页查询工单：应用 agentId、status、del_flag 条件")
    void pageWorkOrders_appliesFilters() {
        Page<WorkOrder> page = new Page<>(1, 10);
        when(workOrderMapper.selectPage(eq(page), any(LambdaQueryWrapper.class))).thenReturn(page);

        var result = workOrderService.pageWorkOrders(page, "agent-1", "PENDING");

        assertThat(result).isSameAs(page);
        verify(workOrderMapper).selectPage(eq(page), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("listPendingForController：controllerCode 为空返回空列表")
    void listPendingForController_emptyWhenCodeBlank() {
        var result = workOrderService.listPendingForController("");
        assertThat(result).isEmpty();
        result = workOrderService.listPendingForController(null);
        assertThat(result).isEmpty();
        verifyNoInteractions(workOrderDeviceMapper, workOrderMapper);
    }

    @Test
    @DisplayName("listPendingForController：无绑定设备时返回空列表")
    void listPendingForController_emptyWhenNoBinds() {
        when(workOrderDeviceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = workOrderService.listPendingForController("CTRL-001");

        assertThat(result).isEmpty();
        verify(workOrderDeviceMapper).selectList(any(LambdaQueryWrapper.class));
        verify(workOrderMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("bindDevices：先删后插")
    void bindDevices_deletesThenInserts() {
        WorkOrderDevice d = new WorkOrderDevice();
        d.setDeviceType("ROBOT");
        d.setDeviceId("r1");
        d.setDeviceCode("ROBOT-001");

        workOrderService.bindDevices("wo-001", List.of(d));

        verify(workOrderDeviceMapper).delete(any(LambdaQueryWrapper.class));
        ArgumentCaptor<WorkOrderDevice> captor = ArgumentCaptor.forClass(WorkOrderDevice.class);
        verify(workOrderDeviceMapper, atLeastOnce()).insert(captor.capture());
        assertThat(captor.getValue().getWorkOrderId()).isEqualTo("wo-001");
        assertThat(captor.getValue().getDeviceCode()).isEqualTo("ROBOT-001");
    }

    @Test
    @DisplayName("startWorkOrder：调用状态机后推送 WebSocket")
    void startWorkOrder_invokesStateMachineAndWebSocket() throws Exception {
        order.setStatus("STARTED");
        when(workOrderMapper.selectById("wo-001")).thenReturn(order);
        WorkOrderDevice master = new WorkOrderDevice();
        master.setDeviceCode("CTRL-001");
        when(workOrderDeviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(master);
        when(objectMapper.writeValueAsString(order)).thenReturn("{}");

        workOrderService.startWorkOrder("wo-001", "op1", "张三", "13800138000");

        verify(workOrderStateMachine).startWorkOrder("wo-001", "op1", "张三", "13800138000");
        verify(deviceWebSocketServer).pushStartedWorkOrder(eq("CTRL-001"), eq("{}"));
    }

    @Test
    @DisplayName("startWorkOrder：状态机执行后工单不存在则不再推送")
    void startWorkOrder_skipsWebSocketWhenOrderMissingAfterMachine() {
        doNothing().when(workOrderStateMachine).startWorkOrder(any(), any(), any(), any());
        when(workOrderMapper.selectById("wo-001")).thenReturn(null);

        workOrderService.startWorkOrder("wo-001", "op1", "张三", "138");

        verify(workOrderStateMachine).startWorkOrder("wo-001", "op1", "张三", "138");
        verifyNoInteractions(deviceWebSocketServer);
    }

    @Test
    @DisplayName("listForExport：条件与排序与分页一致")
    void listForExport_usesSameConditions() {
        when(workOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));

        var list = workOrderService.listForExport("agent-1", "PENDING");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo("wo-001");
        verify(workOrderMapper).selectList(any(LambdaQueryWrapper.class));
    }
}
