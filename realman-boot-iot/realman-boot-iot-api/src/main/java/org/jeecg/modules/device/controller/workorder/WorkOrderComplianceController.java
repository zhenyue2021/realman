package org.jeecg.modules.device.controller.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.util.ContentDispositionUtil;
import org.jeecg.modules.device.util.RequestUtil;
import org.jeecg.modules.device.api.WorkOrderComplianceApiService;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigDetailDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigPageVo;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceCreateDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceQueryDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.util.WorkOrderExcelExportUtil;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
    @Operation(summary = "分页查询工单合规配置", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "1")
    }))
    public ApiResult<IPage<WorkOrderComplianceConfigPageVo>> page(@RequestBody WorkOrderComplianceQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        Page<WorkOrderComplianceConfigPageVo> page = new Page<>(pageNo, pageSize);
        return ApiResult.ok(apiService.pageConfigs(page, query));
    }

    @PostMapping("/add")
    @Operation(summary = "新增工单合规配置", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "2")
    }))
    public ApiResult<WorkOrderComplianceConfig> create(@RequestBody @Validated WorkOrderComplianceCreateDTO config,
                                                       HttpServletRequest request) {
        String operator = RequestUtil.safeUsername(request);
        WorkOrderComplianceConfig created = apiService.create(config, operator);
        return ApiResult.ok(created);
    }
    @GetMapping("/tenants")
    @Operation(summary = "租户下拉列表", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "6")
    }))
    public ApiResult<List<OptionDTO>> tenantOptions(HttpServletRequest request) {
        return ApiResult.ok(apiService.tenantOptions(request));
    }


    @GetMapping("/enterprises/tree")
    @Operation(summary = "企业下拉树", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "7")
    }))
    public ApiResult<List<OptionTreeDTO>> enterpriseOptionsTree(HttpServletRequest request) {
        return ApiResult.ok(apiService.enterpriseOptionsTree(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "工单合规配置详情", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "3")
    }))
    public ApiResult<WorkOrderComplianceConfigDetailDTO> detail(@PathVariable String id) {
        return ApiResult.ok(apiService.detail(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改工单合规配置", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "4")
    }))
    public ApiResult<WorkOrderComplianceConfig> update(@PathVariable String id,
                                                       @RequestBody @Validated WorkOrderComplianceCreateDTO config,
                                                       HttpServletRequest request) {
        String operator = RequestUtil.safeUsername(request);
        WorkOrderComplianceConfig updated = apiService.update(id, config, operator);
        return ApiResult.ok(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除工单合规配置", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "5")
    }))
    public ApiResult<Void> delete(@PathVariable String id) {
        apiService.delete(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/export")
    @Operation(summary = "导出工单合规配置", extensions = @Extension(properties = {
            @ExtensionProperty(name = "x-order", value = "8")
    }))
    public ResponseEntity<byte[]> export(@RequestBody WorkOrderComplianceQueryDTO query) {
        List<WorkOrderComplianceConfig> list = apiService.listForExport(query);
        byte[] bytes = WorkOrderExcelExportUtil.exportComplianceConfigs(list);
        String filename = "work_order_compliance_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(filename))
                .body(bytes);
    }
}

