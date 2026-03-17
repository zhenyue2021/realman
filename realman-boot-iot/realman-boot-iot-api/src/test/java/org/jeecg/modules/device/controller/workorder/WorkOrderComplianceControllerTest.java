package org.jeecg.modules.device.controller.workorder;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.api.WorkOrderComplianceApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkOrderComplianceController.class)
class WorkOrderComplianceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkOrderComplianceApiService apiService;

    @Test
    void page_returnsOk() throws Exception {
        when(apiService.pageConfigs(org.mockito.ArgumentMatchers.<Page<org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig>>any(), any()))
                .thenReturn(new Page<>(1, 20));
        mockMvc.perform(post("/api/work-order/compliance/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNo\":1,\"pageSize\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(apiService).pageConfigs(org.mockito.ArgumentMatchers.<Page<org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig>>any(), any());
    }

    @Test
    void delete_returnsOk() throws Exception {
        // delete 返回 200 即可（controller 调用 apiService.delete）
        mockMvc.perform(delete("/api/work-order/compliance/cfg-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(apiService).delete("cfg-1");
    }
}
