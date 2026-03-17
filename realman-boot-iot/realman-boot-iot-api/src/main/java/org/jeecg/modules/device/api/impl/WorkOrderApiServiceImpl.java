package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.WorkOrderApiService;
import org.jeecg.modules.device.dto.workorder.WorkOrderCreateDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderDetailDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderDeviceDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkOrderApiServiceImpl implements WorkOrderApiService {

    private static final DateTimeFormatter FORMATTER_HMS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IWorkOrderService workOrderService;
    private final IIotDeviceService deviceService;

    @Override
    public WorkOrder create(WorkOrderCreateDTO dto, String operator) {
        WorkOrder order = new WorkOrder();
        order.setAgentId(dto.getAgentId());
        order.setAgentName(dto.getAgentName());
        order.setDepartmentId(dto.getDepartmentId());
        order.setDepartmentName(dto.getDepartmentName());
        order.setComplianceId(dto.getComplianceId());
        order.setRemark(dto.getRemark());
        order.setTenantId(dto.getTenantId());
        order.setPlanStartTime(LocalDateTime.parse(dto.getPlanStartTime(), FORMATTER_HMS));
        order.setPlanEndTime(LocalDateTime.parse(dto.getPlanEndTime(), FORMATTER_HMS));
        order.setStatus("PENDING");
        order.setCreateBy(operator);

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

        return workOrderService.createWorkOrderWithDevices(order, devices);
    }

    @Override
    public WorkOrder edit(String workOrderId, WorkOrderCreateDTO dto, String operator) {
        WorkOrder order = new WorkOrder();
        order.setId(workOrderId);
        order.setAgentId(dto.getAgentId());
        order.setAgentName(dto.getAgentName());
        order.setDepartmentId(dto.getDepartmentId());
        order.setDepartmentName(dto.getDepartmentName());
        order.setComplianceId(dto.getComplianceId());
        order.setRemark(dto.getRemark());
        order.setPlanStartTime(LocalDateTime.parse(dto.getPlanStartTime(), FORMATTER_HMS));
        order.setPlanEndTime(LocalDateTime.parse(dto.getPlanEndTime(), FORMATTER_HMS));
        order.setUpdateBy(operator);

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

        return workOrderService.editWorkOrderWithDevices(order, devices);
    }

    @Override
    public WorkOrderDetailDTO getWorkOrderDetail(String workOrderId) {
        WorkOrderDetailDTO dto = new WorkOrderDetailDTO();
        WorkOrder workOrder = workOrderService.getById(workOrderId);
        BeanUtil.copyProperties(workOrder, dto);
        List<WorkOrderDevice> devices = workOrderService.findDevices(workOrderId);
        if (CollectionUtil.isNotEmpty( devices)) {
            dto.setDevices(devices.stream().map(d -> {
                WorkOrderDeviceDTO device = new WorkOrderDeviceDTO();
                BeanUtil.copyProperties(d, device);
                // 补充设备关键信息：状态、状态文本、型号、版本、位置等
                String bindDeviceId = Objects.nonNull(d.getActualDeviceId()) && StrUtil.isNotBlank(d.getActualDeviceId())
                        ? d.getActualDeviceId()
                        : d.getDeviceId();
                if (bindDeviceId != null) {
                    IotDevice iotDevice = deviceService.getById(bindDeviceId);
                    if (iotDevice != null) {
                        Integer status = iotDevice.getStatus();
                        device.setStatus(status);
                        device.setStatusDictText(resolveStatusText(status));
                        device.setDeviceModel(iotDevice.getDeviceModel());
                        device.setFirmwareVersion(iotDevice.getFirmwareVersion());
                        device.setLongitude(iotDevice.getLongitude() != null ? iotDevice.getLongitude().toPlainString() : null);
                        device.setLatitude(iotDevice.getLatitude() != null ? iotDevice.getLatitude().toPlainString() : null);
                    }
                }
                return device;
            }).collect(Collectors.toList()));
        }
        return dto;
    }

    /**
     * 将设备状态码转换为可读文本。
     *
     * 0-未激活 1-在线 2-离线 3-禁用
     */
    private String resolveStatusText(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "未激活";
            case 1 -> "在线";
            case 2 -> "离线";
            case 3 -> "禁用";
            default -> String.valueOf(status);
        };
    }
}

