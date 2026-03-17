package org.jeecg.modules.device.controller.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.device.api.WorkOrderComplianceApiService;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceQueryDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 工单合规配置管理
 */
@RestController
@RequestMapping("/api/work-order/compliance")
@RequiredArgsConstructor
@Tag(name = "工单合规配置管理")
public class WorkOrderComplianceController {

    private final WorkOrderComplianceApiService apiService;

    @PostMapping("/page")
    @Operation(summary = "分页查询工单合规配置")
    public ApiResult<IPage<WorkOrderComplianceConfig>> page(@RequestBody WorkOrderComplianceQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        Page<WorkOrderComplianceConfig> page = new Page<>(pageNo, pageSize);
        return ApiResult.ok(apiService.pageConfigs(page, query));
    }

    @PostMapping
    @Operation(summary = "新增工单合规配置")
    public ApiResult<WorkOrderComplianceConfig> create(@RequestBody WorkOrderComplianceConfig config,
                                                       HttpServletRequest request) {
        String operator = null;
        try {
            operator = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        WorkOrderComplianceConfig created = apiService.create(config, operator);
        return ApiResult.ok(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改工单合规配置")
    public ApiResult<WorkOrderComplianceConfig> update(@PathVariable String id,
                                                       @RequestBody WorkOrderComplianceConfig config,
                                                       HttpServletRequest request) {
        String operator = null;
        try {
            operator = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        WorkOrderComplianceConfig updated = apiService.update(id, config, operator);
        return ApiResult.ok(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除工单合规配置（逻辑删除）")
    public ApiResult<Void> delete(@PathVariable String id) {
        apiService.delete(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/export")
    @Operation(summary = "导出工单合规配置Excel")
    public ResponseEntity<byte[]> export(@RequestBody WorkOrderComplianceQueryDTO query) {
        List<WorkOrderComplianceConfig> list = apiService.listForExport(query);
        byte[] bytes = org.jeecg.modules.device.util.WorkOrderExcelExportUtil.exportComplianceConfigs(list);
        String filename = "work_order_compliance_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(bytes);
    }
}

