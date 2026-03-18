package org.jeecg.modules.device.controller.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.ContentDispositionUtil;
import org.jeecg.modules.device.api.WorkOrderApiService;
import org.jeecg.modules.device.dto.workorder.*;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.service.workorder.IWorkOrderAttachmentService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工单管理
 */
@RestController
@RequestMapping("/api/work-order")
@RequiredArgsConstructor
@Tag(name = "工单管理")
public class WorkOrderController {

    private final IWorkOrderService workOrderService;
    private final IWorkOrderAttachmentService attachmentService;
    private final WorkOrderApiService workOrderApiService;

    @PostMapping("/page")
    @Operation(summary = "分页查询工单")
    public ApiResult<IPage<WorkOrderPageItemDTO>> page(HttpServletRequest request, @RequestBody WorkOrderQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        Page<WorkOrder> page = new Page<>(pageNo, pageSize);
        String tenantId = request.getHeader("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }

        IPage<WorkOrderPageItemDTO> result = workOrderApiService.pageWorkOrders(page, tenantId, query);
        return ApiResult.ok(result);
    }

    @PostMapping("/export")
    @Operation(summary = "导出工单列表Excel")
    public ResponseEntity<byte[]> export(HttpServletRequest request, @RequestBody WorkOrderQueryDTO query) {
        String tenantId = request.getHeader("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }
        byte[] bytes = org.jeecg.modules.device.util.WorkOrderExcelExportUtil.exportWorkOrders(
                workOrderService.listForExport(tenantId, query.getStatus()));
        String filename = "work_order_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(filename))
                .body(bytes);
    }

    @PostMapping("/add")
    @Operation(summary = "创建工单")
    public ApiResult<WorkOrder> create(@RequestBody WorkOrderCreateDTO dto, HttpServletRequest request) {
        String operator = null;
        try {
            operator = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        String tenantId = request.getHeader("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }
        dto.setTenantId(tenantId);
        WorkOrder created = workOrderApiService.create(dto, operator);
        return ApiResult.ok(created);
    }

    @PostMapping("/{id}/edit")
    @Operation(summary = "编辑工单（基础信息与绑定设备）")
    public ApiResult<WorkOrder> edit(@PathVariable String id,
                                     @RequestBody WorkOrderCreateDTO dto,
                                     HttpServletRequest request) {
        String operator = null;
        try {
            operator = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        WorkOrder updated = workOrderApiService.edit(id, dto, operator);
        return ApiResult.ok(updated);
    }

    @GetMapping("/{id}")
    @Operation(summary = "工单详情")
    public ApiResult<WorkOrderDetailDTO> detail(HttpServletRequest request, @PathVariable String id) {
        WorkOrderDetailDTO workOrder = workOrderApiService.getWorkOrderDetail(id);
        return ApiResult.ok(workOrder);
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "开始工单")
    public ApiResult<Void> start(HttpServletRequest request, @PathVariable String id, @RequestBody WorkOrderStartDTO dto) {
        workOrderService.startWorkOrder(id, dto.getOperatorId(), dto.getOperatorName(), dto.getOperatorPhone());
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交工单")
    public ApiResult<Void> submit(@PathVariable String id) {
        workOrderService.submitWorkOrder(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/timeout-reason")
    @Operation(summary = "填写超时原因")
    public ApiResult<Void> timeoutReason(HttpServletRequest request, @PathVariable String id, @RequestBody WorkOrderTimeoutReasonDTO dto) {
        workOrderService.fillTimeoutReason(id, dto.getReason(), dto.getSource());
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/audit")
    @Operation(summary = "审核工单")
    public ApiResult<Void> audit(@PathVariable String id, @RequestBody WorkOrderAuditDTO dto,
                                 HttpServletRequest request) {
        String auditor = null;
        try {
            auditor = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        workOrderService.auditWorkOrder(id, dto.getResult(), dto.getComment(), auditor);
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭工单")
    public ApiResult<Void> close(@PathVariable String id, @RequestBody WorkOrderTimeoutReasonDTO dto,
                                 HttpServletRequest request) {
        String closer = null;
        try {
            closer = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        workOrderService.closeWorkOrder(id, dto.getReason(), closer);
        return ApiResult.ok(null);
    }

    @GetMapping("/pending/controller/{controllerCode}")
    @Operation(summary = "主控端待开始工单列表")
    public ApiResult<List<WorkOrder>> pendingForController(HttpServletRequest request, @PathVariable String controllerCode) {
        return ApiResult.ok(workOrderService.listPendingForController(controllerCode));
    }

    @PostMapping("/{id}/attachments")
    @Operation(summary = "新增工单附件")
    public ApiResult<Void> addAttachments(@PathVariable String id,
                                          @RequestBody List<WorkOrderAttachmentDTO> dtos,
                                          HttpServletRequest request) {
        String username = null;
        try {
            username = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
        }
        List<WorkOrderAttachment> list = dtos == null ? List.of() : dtos.stream().map(d -> {
            WorkOrderAttachment a = new WorkOrderAttachment();
            a.setFileName(d.getFileName());
            a.setFileUrl(d.getFileUrl());
            a.setDescription(d.getDescription());
            return a;
        }).collect(Collectors.toList());
        attachmentService.addAttachments(id, username, list);
        return ApiResult.ok(null);
    }
}

