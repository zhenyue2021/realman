package org.jeecg.modules.device.controller.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.service.workorder.IWorkOrderAttachmentService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单管理 Controller 单元测试
 */
@WebMvcTest(WorkOrderController.class)
class WorkOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IWorkOrderService workOrderService;
    @MockBean
    private IWorkOrderAttachmentService attachmentService;

    @Test
    @DisplayName("分页查询工单返回 200")
    void page_returnsOk() throws Exception {
        Page<WorkOrder> page = new Page<>(1, 20);
        when(workOrderService.pageWorkOrders(any(Page.class), any(), any())).thenReturn(page);

        mockMvc.perform(post("/api/work-order/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNo\":1,\"pageSize\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).pageWorkOrders(any(Page.class), any(), any());
    }

    @Test
    @DisplayName("工单详情返回 200")
    void detail_returnsOk() throws Exception {
        WorkOrder order = new WorkOrder();
        order.setId("wo-1");
        order.setStatus("PENDING");
        when(workOrderService.getById("wo-1")).thenReturn(order);

        mockMvc.perform(get("/api/work-order/wo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("wo-1"));

        verify(workOrderService).getById("wo-1");
    }

    @Test
    @DisplayName("主控端待开始工单列表返回 200")
    void pendingForController_returnsOk() throws Exception {
        when(workOrderService.listPendingForController("CTRL-001")).thenReturn(List.of());

        mockMvc.perform(get("/api/work-order/pending/controller/CTRL-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).listPendingForController("CTRL-001");
    }

    @Test
    @DisplayName("开始工单调用 service 并返回 200")
    void start_callsServiceAndReturnsOk() throws Exception {
        mockMvc.perform(post("/api/work-order/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"op1\",\"operatorName\":\"张三\",\"operatorPhone\":\"138\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).startWorkOrder(eq("wo-1"), eq("op1"), eq("张三"), eq("138"));
    }

    @Test
    @DisplayName("提交工单调用 service 并返回 200")
    void submit_callsServiceAndReturnsOk() throws Exception {
        mockMvc.perform(post("/api/work-order/wo-1/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).submitWorkOrder("wo-1");
    }

    @Test
    @DisplayName("新增工单附件调用 attachmentService 并返回 200")
    void addAttachments_callsServiceAndReturnsOk() throws Exception {
        mockMvc.perform(post("/api/work-order/wo-1/attachments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"fileName\":\"a.png\",\"fileUrl\":\"https://x/a.png\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(attachmentService).addAttachments(eq("wo-1"), any(), any());
    }
}
