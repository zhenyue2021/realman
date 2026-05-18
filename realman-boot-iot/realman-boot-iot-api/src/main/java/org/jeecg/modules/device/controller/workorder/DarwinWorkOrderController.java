package org.jeecg.modules.device.controller.workorder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.dto.workorder.DarwinWorkOrderItemDTO;
import org.jeecg.modules.device.dto.workorder.DarwinWorkOrderQueryDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderDeviceDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Darwin 工单管理（source=2）
 * 列表/详情由本 Controller 提供；开始/提交复用 {@link WorkOrderController}。
 */
@RestController
@RequestMapping("/api/darwin/work-order")
@RequiredArgsConstructor
@Tag(name = "Darwin 工单管理")
public class DarwinWorkOrderController {

    private final IWorkOrderService workOrderService;
    private final WorkOrderDeviceMapper deviceMapper;

    @PostMapping("/page")
    @Operation(summary = "Darwin 工单分页列表")
    public ApiResult<IPage<DarwinWorkOrderItemDTO>> page(HttpServletRequest request,
                                                         @RequestBody DarwinWorkOrderQueryDTO query) {
        String tenantId = request.getHeader("x-tenant-id");
        if (StrUtil.isBlank(tenantId)) {
            throw new RuntimeException("缺少租户ID（x-tenant-id）");
        }

        int pageNo   = query.getPageNo()   != null ? query.getPageNo()   : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;

        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getDelFlag, 0)
                .eq(WorkOrder::getDarwinDataSource, 2)
                .eq(WorkOrder::getTenantId, tenantId);
        if (StrUtil.isNotBlank(query.getStatus())) {
            wrapper.eq(WorkOrder::getStatus, query.getStatus());
        }
        if (StrUtil.isNotBlank(query.getTaskName())) {
            wrapper.like(WorkOrder::getTaskName, query.getTaskName().trim());
        }
        wrapper.orderByDesc(WorkOrder::getCreateTime);

        IPage<WorkOrder> orderPage = workOrderService.page(new Page<>(pageNo, pageSize), wrapper);
        return ApiResult.ok(convertPage(orderPage));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Darwin 工单详情")
    public ApiResult<DarwinWorkOrderItemDTO> detail(@PathVariable String id) {
        WorkOrder order = workOrderService.getById(id);
        if (order == null) {
            throw new RuntimeException("工单不存在: " + id);
        }
        DarwinWorkOrderItemDTO dto = new DarwinWorkOrderItemDTO();
        BeanUtil.copyProperties(order, dto);
        // Darwin 工单：work_order.id 即 Darwin 工单 ID
        dto.setDarwinOrderId(order.getId());

        List<WorkOrderDevice> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, id));
        fillDevices(dto, devices);
        return ApiResult.ok(dto);
    }

    // -----------------------------------------------------------------------

    private IPage<DarwinWorkOrderItemDTO> convertPage(IPage<WorkOrder> src) {
        List<WorkOrder> orders = src.getRecords();
        if (orders == null || orders.isEmpty()) {
            Page<DarwinWorkOrderItemDTO> empty = new Page<>(src.getCurrent(), src.getSize(), src.getTotal());
            empty.setRecords(List.of());
            return empty;
        }

        List<String> orderIds = orders.stream().map(WorkOrder::getId).filter(Objects::nonNull).toList();

        // 批量加载绑定设备
        Map<String, List<WorkOrderDevice>> devicesByOrderId = deviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .in(WorkOrderDevice::getWorkOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(WorkOrderDevice::getWorkOrderId));

        List<DarwinWorkOrderItemDTO> records = orders.stream().map(o -> {
            DarwinWorkOrderItemDTO dto = new DarwinWorkOrderItemDTO();
            BeanUtil.copyProperties(o, dto);
            // Darwin 工单：work_order.id 即 Darwin 工单 ID
            dto.setDarwinOrderId(o.getId());
            fillDevices(dto, devicesByOrderId.getOrDefault(o.getId(), List.of()));
            return dto;
        }).toList();

        Page<DarwinWorkOrderItemDTO> out = new Page<>(src.getCurrent(), src.getSize(), src.getTotal());
        out.setRecords(records);
        return out;
    }

    private void fillDevices(DarwinWorkOrderItemDTO dto, List<WorkOrderDevice> devices) {
        List<WorkOrderDeviceDTO> robots = new ArrayList<>();
        for (WorkOrderDevice d : devices) {
            String type = d.getDeviceType();
            WorkOrderDeviceDTO item = new WorkOrderDeviceDTO();
            BeanUtil.copyProperties(d, item);
            if ("2".equals(type) || "CONTROLLER".equalsIgnoreCase(type)) {
                dto.setController(item);
            } else if ("1".equals(type) || "ROBOT".equalsIgnoreCase(type)) {
                robots.add(item);
            }
        }
        dto.setRobots(robots);
    }
}
