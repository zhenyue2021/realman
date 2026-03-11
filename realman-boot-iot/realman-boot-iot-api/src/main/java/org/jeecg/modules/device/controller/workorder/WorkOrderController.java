package org.jeecg.modules.device.controller.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.device.dto.workorder.*;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.service.workorder.IWorkOrderAttachmentService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    @PostMapping("/page")
    @Operation(summary = "分页查询工单")
    public ApiResult<IPage<WorkOrder>> page(@RequestBody WorkOrderQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        Page<WorkOrder> page = new Page<>(pageNo, pageSize);
        IPage<WorkOrder> result = workOrderService.pageWorkOrders(page, query.getAgentId(), query.getStatus());
        return ApiResult.ok(result);
    }

    @PostMapping("/export")
    @Operation(summary = "导出工单列表Excel")
    public ResponseEntity<byte[]> export(@RequestBody WorkOrderQueryDTO query) {
        byte[] bytes = org.jeecg.modules.device.util.WorkOrderExcelExportUtil.exportWorkOrders(
                workOrderService.listForExport(query.getAgentId(), query.getStatus()));
        String filename = "work_order_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(bytes);
    }

    @PostMapping
    @Operation(summary = "创建工单")
    public ApiResult<WorkOrder> create(@RequestBody WorkOrderCreateDTO dto, HttpServletRequest request) {
        WorkOrder order = new WorkOrder();
        order.setAgentId(dto.getAgentId());
        order.setAgentName(dto.getAgentName());
        order.setDepartmentId(dto.getDepartmentId());
        order.setDepartmentName(dto.getDepartmentName());
        order.setComplianceId(dto.getComplianceId());
        order.setRemark(dto.getRemark());
        order.setPlanStartTime(dto.getPlanStartTime());
        order.setPlanEndTime(dto.getPlanEndTime());
        order.setStatus("PENDING");
        try {
            String username = JwtUtil.getUserNameByToken(request);
            order.setCreateBy(username);
        } catch (JeecgBootException ignored) {
        }
        workOrderService.save(order);

        List<WorkOrderDevice> devices = dto.getDevices() == null ? List.of() :
                dto.getDevices().stream().map(d -> {
                    WorkOrderDevice w = new WorkOrderDevice();
                    w.setDeviceType(d.getDeviceType());
                    w.setDeviceId(d.getDeviceId());
                    w.setDeviceName(d.getDeviceName());
                    w.setDeviceCode(d.getDeviceCode());
                    w.setActualDeviceId(d.getActualDeviceId());
                    w.setActualDeviceName(d.getActualDeviceName());
                    w.setActualDeviceCode(d.getActualDeviceCode());
                    return w;
                }).collect(Collectors.toList());
        workOrderService.bindDevices(order.getId(), devices);
        return ApiResult.ok(order);
    }

    @GetMapping("/{id}")
    @Operation(summary = "工单详情")
    public ApiResult<WorkOrder> detail(@PathVariable String id) {
        return ApiResult.ok(workOrderService.getById(id));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "开始工单")
    public ApiResult<Void> start(@PathVariable String id, @RequestBody WorkOrderStartDTO dto) {
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
    public ApiResult<Void> timeoutReason(@PathVariable String id, @RequestBody WorkOrderTimeoutReasonDTO dto) {
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
    public ApiResult<List<WorkOrder>> pendingForController(@PathVariable String controllerCode) {
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

