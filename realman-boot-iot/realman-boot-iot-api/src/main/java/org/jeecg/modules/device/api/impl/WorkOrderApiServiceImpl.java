package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.WorkOrderApiService;
import org.jeecg.modules.device.dto.AuthorizedDeviceOptionDTO;
import org.jeecg.modules.device.dto.workorder.*;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkOrderApiServiceImpl implements WorkOrderApiService {

    private static final DateTimeFormatter FORMATTER_HMS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IWorkOrderService workOrderService;
    private final IIotDeviceService deviceService;
    private final IWorkOrderComplianceConfigService complianceConfigService;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final IotDeviceMapper iotDeviceMapper;
    private final SysAuthFeignClient sysAuthFeignClient;

    @Override
    public WorkOrder create(WorkOrderCreateDTO dto, String operator) {
        WorkOrder order = new WorkOrder();
        order.setAgentId(dto.getAgentId());
        order.setAgentName(dto.getAgentName());
        order.setTaskName(dto.getTaskName());
        order.setCurrency(dto.getCurrency());
        if (StrUtil.isNotBlank(dto.getUnitPrice())) {
            order.setUnitPrice(new java.math.BigDecimal(dto.getUnitPrice()));
        }
        if (StrUtil.isNotBlank(dto.getTotalPrice())) {
            order.setTotalPrice(new java.math.BigDecimal(dto.getTotalPrice()));
        }
        order.setDepartmentId(dto.getDepartmentId());
        order.setDepartmentName(dto.getDepartmentName());
        order.setComplianceId(dto.getComplianceId());
        order.setRemark(dto.getRemark());
        order.setTenantId(dto.getTenantId());
        order.setPlanStartTime(LocalDateTime.parse(dto.getPlanStartTime(), FORMATTER_HMS));
        order.setPlanEndTime(LocalDateTime.parse(dto.getPlanEndTime(), FORMATTER_HMS));
        order.setStatus("PENDING");
        order.setCreateBy(operator);
        markComplianceApplied(dto.getComplianceId(), operator);

        List<WorkOrderDevice> devices = dto.getDevices() == null ? List.of() :
                dto.getDevices().stream().map(d -> {
                    WorkOrderDevice w = new WorkOrderDevice();
                    w.setDeviceType(d.getDeviceType());
                    w.setDeviceId(d.getDeviceId());
                    w.setDeviceName(d.getDeviceName());
                    w.setDeviceCode(d.getDeviceCode());
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
        order.setTaskName(dto.getTaskName());
        order.setCurrency(dto.getCurrency());
        if (StrUtil.isNotBlank(dto.getUnitPrice())) {
            order.setUnitPrice(new java.math.BigDecimal(dto.getUnitPrice()));
        }
        if (StrUtil.isNotBlank(dto.getTotalPrice())) {
            order.setTotalPrice(new java.math.BigDecimal(dto.getTotalPrice()));
        }
        order.setDepartmentId(dto.getDepartmentId());
        order.setDepartmentName(dto.getDepartmentName());
        order.setComplianceId(dto.getComplianceId());
        order.setRemark(dto.getRemark());
        order.setPlanStartTime(LocalDateTime.parse(dto.getPlanStartTime(), FORMATTER_HMS));
        order.setPlanEndTime(LocalDateTime.parse(dto.getPlanEndTime(), FORMATTER_HMS));
        order.setUpdateBy(operator);
        markComplianceApplied(dto.getComplianceId(), operator);

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
//                        device.setStatusDictText(resolveStatusText(status));
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

    @Override
    public IPage<WorkOrderPageItemDTO> pageWorkOrders(Page<WorkOrder> page, String tenantId, WorkOrderQueryDTO query) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrder::getDelFlag, 0);
        wrapper.eq(WorkOrder::getTenantId, tenantId);
        if (query != null && StrUtil.isNotBlank(query.getAgentId())) {
            wrapper.eq(WorkOrder::getAgentId, query.getAgentId());
        }
        if (query != null && StrUtil.isNotBlank(query.getStatus())) {
            wrapper.eq(WorkOrder::getStatus, query.getStatus());
        }
        if (query != null && StrUtil.isNotBlank(query.getTaskName())) {
            wrapper.like(WorkOrder::getTaskName, query.getTaskName().trim());
        }
        if (query != null && StrUtil.isNotBlank(query.getCurrency())) {
            wrapper.eq(WorkOrder::getCurrency, query.getCurrency().trim());
        }
        if (query != null && StrUtil.isNotBlank(query.getWorkOrderId())) {
            wrapper.like(WorkOrder::getId, query.getWorkOrderId().trim());
        }
        if (query != null && StrUtil.isNotBlank(query.getOperatorId())) {
            wrapper.eq(WorkOrder::getOperatorId, query.getOperatorId().trim());
        }
        if (query != null && StrUtil.isNotBlank(query.getOperatorName())) {
            wrapper.like(WorkOrder::getOperatorName, query.getOperatorName().trim());
        }
        if (query != null) {
            applyTimeRange(wrapper, WorkOrder::getPlanStartTime, query.getPlanStartTimeBegin(), query.getPlanStartTimeEnd());
            applyTimeRange(wrapper, WorkOrder::getPlanEndTime, query.getPlanEndTimeBegin(), query.getPlanEndTimeEnd());
        }

        // 主控/机器人过滤：通过 work_order_device 反查工单ID集合
        Set<String> filteredOrderIds = null;
        if (query != null && StrUtil.isNotBlank(query.getControllerCode())) {
            Set<String> ids = findOrderIdsByDeviceCodeLike(query.getControllerCode().trim(), true);
            if (ids.isEmpty()) {
                return emptyPage(page);
            }
            filteredOrderIds = ids;
        }
        if (query != null && StrUtil.isNotBlank(query.getRobotCode())) {
            Set<String> ids = findOrderIdsByDeviceCodeLike(query.getRobotCode().trim(), false);
            if (ids.isEmpty()) {
                return emptyPage(page);
            }
            filteredOrderIds = filteredOrderIds == null ? ids : intersect(filteredOrderIds, ids);
            if (filteredOrderIds.isEmpty()) {
                return emptyPage(page);
            }
        }
        if (filteredOrderIds != null) {
            wrapper.in(WorkOrder::getId, filteredOrderIds);
        }

        wrapper.orderByDesc(WorkOrder::getCreateTime);
        IPage<WorkOrder> orderPage = workOrderService.page(page, wrapper);
        if (orderPage.getRecords() == null || orderPage.getRecords().isEmpty()) {
            return convertEmpty(orderPage, page);
        }

        List<WorkOrder> orders = orderPage.getRecords();
        List<String> orderIds = orders.stream().map(WorkOrder::getId).filter(Objects::nonNull).distinct().toList();
        List<String> complianceIds = orders.stream().map(WorkOrder::getComplianceId).filter(StrUtil::isNotBlank).distinct().toList();

        Map<String, WorkOrderComplianceConfig> cfgMap = complianceIds.isEmpty()
                ? Map.of()
                : complianceConfigService.listByIds(complianceIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(WorkOrderComplianceConfig::getId, Function.identity(), (a, b) -> a));

        Map<String, List<WorkOrderDevice>> devicesByOrderId = orderIds.isEmpty()
                ? Map.of()
                : workOrderDeviceMapper.selectList(new LambdaQueryWrapper<WorkOrderDevice>()
                .in(WorkOrderDevice::getWorkOrderId, orderIds)).stream()
                .collect(Collectors.groupingBy(WorkOrderDevice::getWorkOrderId));

        Map<String, IotDevice> iotDeviceMap = loadBindIotDevices(devicesByOrderId.values());

        List<WorkOrderPageItemDTO> dtoRecords = orders.stream().map(o -> {
            WorkOrderPageItemDTO dto = new WorkOrderPageItemDTO();
            BeanUtil.copyProperties(o, dto);
            dto.setUnitPrice(o.getUnitPrice() != null ? o.getUnitPrice().toPlainString() : null);
            dto.setTotalPrice(o.getTotalPrice() != null ? o.getTotalPrice().toPlainString() : null);

            WorkOrderComplianceConfig cfg = cfgMap.get(o.getComplianceId());
            if (cfg != null) {
                WorkOrderComplianceConfigDetailDTO configDetailDTO = new WorkOrderComplianceConfigDetailDTO();
                BeanUtil.copyProperties(cfg, configDetailDTO);
                dto.setCompliance(configDetailDTO);
            }

            List<WorkOrderDevice> binds = devicesByOrderId.getOrDefault(o.getId(), List.of());
            WorkOrderDevice controllerBind = pickController(binds);
            if (controllerBind != null) {
                dto.setController(toDeviceDTO(controllerBind, iotDeviceMap));
            }
            List<WorkOrderDeviceDTO> robots = binds.stream()
                    .filter(this::isRobot)
                    .map(d -> toDeviceDTO(d, iotDeviceMap))
                    .toList();
            dto.setRobots(robots);
            return dto;
        }).toList();

        Page<WorkOrderPageItemDTO> dtoPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        dtoPage.setRecords(dtoRecords);
        return dtoPage;
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

    private static String format(LocalDateTime t) {
        return t != null ? t.format(FORMATTER_HMS) : null;
    }

    private static <T> IPage<T> emptyPage(Page<?> page) {
        Page<T> p = new Page<>(page.getCurrent(), page.getSize(), 0);
        p.setRecords(List.of());
        return p;
    }

    private static <T> IPage<T> convertEmpty(IPage<?> src, Page<?> req) {
        Page<T> p = new Page<>(req.getCurrent(), req.getSize(), src.getTotal());
        p.setRecords(List.of());
        return p;
    }

    private static void applyTimeRange(LambdaQueryWrapper<WorkOrder> wrapper,
                                       com.baomidou.mybatisplus.core.toolkit.support.SFunction<WorkOrder, LocalDateTime> column,
                                       String begin, String end) {
        if (StrUtil.isNotBlank(begin)) {
            wrapper.ge(column, LocalDateTime.parse(begin.trim(), FORMATTER_HMS));
        }
        if (StrUtil.isNotBlank(end)) {
            wrapper.le(column, LocalDateTime.parse(end.trim(), FORMATTER_HMS));
        }
    }

    private Set<String> findOrderIdsByDeviceCodeLike(String codeLike, boolean controller) {
        if (StrUtil.isBlank(codeLike)) {
            return Set.of();
        }
        List<String> types = controller ? List.of("2", "CONTROLLER") : List.of("1", "ROBOT");
        List<WorkOrderDevice> rows = workOrderDeviceMapper.selectList(new LambdaQueryWrapper<WorkOrderDevice>()
                .in(WorkOrderDevice::getDeviceType, types)
                .and(w -> w.like(WorkOrderDevice::getDeviceCode, codeLike)
                        .or().like(WorkOrderDevice::getActualDeviceCode, codeLike)));
        return rows.stream()
                .map(WorkOrderDevice::getWorkOrderId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Set.of();
        }
        Set<String> out = a.size() <= b.size() ? new java.util.HashSet<>(a) : new java.util.HashSet<>(b);
        out.retainAll(a.size() <= b.size() ? b : a);
        return out;
    }

    private boolean isController(WorkOrderDevice d) {
        if (d == null || StrUtil.isBlank(d.getDeviceType())) return false;
        return "2".equals(d.getDeviceType()) || "CONTROLLER".equalsIgnoreCase(d.getDeviceType());
    }

    private boolean isRobot(WorkOrderDevice d) {
        if (d == null || StrUtil.isBlank(d.getDeviceType())) return false;
        return "1".equals(d.getDeviceType()) || "ROBOT".equalsIgnoreCase(d.getDeviceType());
    }

    private WorkOrderDevice pickController(List<WorkOrderDevice> binds) {
        if (binds == null || binds.isEmpty()) return null;
        for (WorkOrderDevice d : binds) {
            if (isController(d)) return d;
        }
        return null;
    }

    private Map<String, IotDevice> loadBindIotDevices(Collection<List<WorkOrderDevice>> groups) {
        if (groups == null || groups.isEmpty()) return Map.of();
        List<String> ids = new ArrayList<>();
        for (List<WorkOrderDevice> list : groups) {
            if (list == null) continue;
            for (WorkOrderDevice d : list) {
                String bindId = StrUtil.isNotBlank(d.getActualDeviceId()) ? d.getActualDeviceId() : d.getDeviceId();
                if (StrUtil.isNotBlank(bindId)) {
                    ids.add(bindId);
                }
            }
        }
        if (ids.isEmpty()) return Map.of();
        return deviceService.listByIds(ids).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(IotDevice::getId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    private WorkOrderDeviceDTO toDeviceDTO(WorkOrderDevice d, Map<String, IotDevice> iotDeviceMap) {
        WorkOrderDeviceDTO dto = new WorkOrderDeviceDTO();
        if (d != null) {
            BeanUtil.copyProperties(d, dto);
            String bindDeviceId = StrUtil.isNotBlank(d.getActualDeviceId()) ? d.getActualDeviceId() : d.getDeviceId();
            if (StrUtil.isNotBlank(bindDeviceId)) {
                IotDevice iot = iotDeviceMap.get(bindDeviceId);
                if (iot != null) {
                    Integer status = iot.getStatus();
                    dto.setStatus(status);
                    dto.setDeviceModel(iot.getDeviceModel());
                    dto.setFirmwareVersion(iot.getFirmwareVersion());
                    dto.setLongitude(iot.getLongitude() != null ? iot.getLongitude().toPlainString() : null);
                    dto.setLatitude(iot.getLatitude() != null ? iot.getLatitude().toPlainString() : null);
                }
            }
        }
        return dto;
    }

    @Override
    public List<WorkOrderComplianceConfig> listConfigsByEnterprise(String username) {
        List<String> enterpriseIds = resolveEnterpriseIds(username);
        if (enterpriseIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WorkOrderComplianceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(WorkOrderComplianceConfig::getEnterpriseId, enterpriseIds);
        wrapper.eq(WorkOrderComplianceConfig::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrderComplianceConfig::getCreateTime);
        return complianceConfigService.list(wrapper);
    }

    @Override
    public List<AuthorizedDeviceOptionDTO> listAuthorizedControllers(String username) {
        List<String> enterpriseIds = resolveEnterpriseIds(username);
        if (enterpriseIds.isEmpty()) {
            return List.of();
        }
        return iotDeviceMapper.listAuthorizedDevicesByEnterprise(enterpriseIds, 2);
    }

    @Override
    public List<AuthorizedDeviceOptionDTO> listAuthorizedRobots(String username) {
        List<String> enterpriseIds = resolveEnterpriseIds(username);
        if (enterpriseIds.isEmpty()) {
            return List.of();
        }
        return iotDeviceMapper.listAuthorizedDevicesByEnterprise(enterpriseIds, 1);
    }

    /**
     * 通过用户名查询其所属的全部有效企业 ID 列表。
     * 关联 sys_user，sys_user_depart 和 sys_depart，过滤状态不可用的部门。
     */
    private List<String> resolveEnterpriseIds(String username) {
        if (StrUtil.isBlank(username)) {
            return List.of();
        }
        List<String> ids = sysAuthFeignClient.listValidEnterpriseIdsByUsername(username);
        return ids == null ? List.of() : ids;
    }

    private void markComplianceApplied(String complianceId, String operator) {
        if (StrUtil.isBlank(complianceId)) {
            return;
        }
        WorkOrderComplianceConfig cfg = complianceConfigService.getById(complianceId);
        if (cfg == null) {
            return;
        }
        if (cfg.getApplyStatus() != null && cfg.getApplyStatus() == 1) {
            return;
        }
        WorkOrderComplianceConfig update = new WorkOrderComplianceConfig();
        update.setId(complianceId);
        update.setApplyStatus(1);
        update.setUpdateBy(operator);
        complianceConfigService.updateById(update);
    }
}

