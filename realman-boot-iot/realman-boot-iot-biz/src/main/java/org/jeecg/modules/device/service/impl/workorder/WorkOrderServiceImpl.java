package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.WorkOrderConstant;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.datacollect.entity.WorkOrderMapping;
import org.jeecg.modules.device.datacollect.mapper.WorkOrderMappingMapper;
import org.jeecg.modules.device.dto.WorkOrderOperationRecordDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.service.workorder.IWorkOrderStateMachineService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderServiceImpl extends ServiceImpl<WorkOrderMapper, WorkOrder>
        implements IWorkOrderService {

    private final ObjectMapper objectMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final IWorkOrderStateMachineService workOrderStateMachine;
    private final IotDeviceMapper iotDeviceMapper;
    private final WorkOrderMappingMapper darwinMappingMapper;

    @Override
    public IPage<WorkOrder> pageWorkOrders(Page<WorkOrder> page, String agentId, String status) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null && !agentId.isEmpty()) {
            wrapper.eq(WorkOrder::getAgentId, agentId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        wrapper.eq(WorkOrder::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrder::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    public List<WorkOrder> listForExport(String agentId, String status) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null && !agentId.isEmpty()) {
            wrapper.eq(WorkOrder::getAgentId, agentId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        wrapper.eq(WorkOrder::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrder::getCreateTime);
        return this.list(wrapper);
    }

    @Override
    public List<WorkOrder> listPendingForController(String controllerCode) {
        return listPendingForControllerAndDepartments(controllerCode, null);
    }

    @Override
    public List<WorkOrder> listPendingForControllerAndDepartments(String controllerCode, List<String> departmentIds) {
        if (controllerCode == null || controllerCode.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WorkOrderDevice> deviceWrapper = new LambdaQueryWrapper<WorkOrderDevice>()
                .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER)
                .eq(WorkOrderDevice::getDeviceCode, controllerCode);
        List<WorkOrderDevice> binds = workOrderDeviceMapper.selectList(deviceWrapper);
        if (binds.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = binds.stream()
                .map(WorkOrderDevice::getWorkOrderId)
                .distinct()
                .toList();

        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getId, orderIds)
                .in(WorkOrder::getStatus, WorkOrderConstant.ORDER_STATUS.PENDING, WorkOrderConstant.ORDER_STATUS.STARTED)
                .eq(WorkOrder::getDelFlag, 0)
                .orderByAsc(WorkOrder::getPlanStartTime);
        if (departmentIds != null && !departmentIds.isEmpty()) {
            wrapper.in(WorkOrder::getDepartmentId, departmentIds);
        }
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDevices(String workOrderId, List<WorkOrderDevice> devices) {
        workOrderDeviceMapper.delete(new LambdaQueryWrapper<WorkOrderDevice>()
                .eq(WorkOrderDevice::getWorkOrderId, workOrderId));
        if (devices == null || devices.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (WorkOrderDevice d : devices) {
            d.setId(null);
            d.setWorkOrderId(workOrderId);
            d.setCreateTime(now);
            workOrderDeviceMapper.insert(d);
        }
    }

    @Override
    public List<WorkOrderDevice> findDevices(String workOrderId) {
        return workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId));
    }

    @Override
    public WorkOrderDevice findMasterDevice(String workOrderId) {
        return workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId).eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER).last("LIMIT 1"));
    }

    @Override
    public void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone) {
        workOrderStateMachine.startWorkOrder(workOrderId, operatorId, operatorName, operatorPhone);
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        WorkOrderDevice master = findMasterDevice(workOrderId);
        if (master == null || master.getDeviceCode() == null) {
            log.warn("[startWorkOrder] 无主控设备，跳过 WebSocket 推送: workOrderId={}", workOrderId);
            return;
        }
        try {
            String workOrderJson = objectMapper.writeValueAsString(order);
            deviceWebSocketServer.pushStartedWorkOrder(master.getDeviceCode(), workOrderJson);
        } catch (Exception e) {
            log.warn("[startWorkOrder] WebSocket 推送工单失败: workOrderId={}, err={}", order.getId(), e.getMessage());
        }
    }

    @Override
    public void submitWorkOrder(String workOrderId, String operator) {
        workOrderStateMachine.submitWorkOrder(workOrderId, operator);
    }

    @Override
    public void fillTimeoutReason(String workOrderId, String reason, String source) {
        workOrderStateMachine.fillTimeoutReason(workOrderId, reason, source);
    }

    @Override
    public void auditWorkOrder(String workOrderId, String result, String comment, String auditor) {
        workOrderStateMachine.auditWorkOrder(workOrderId, result, comment, auditor);
    }

    @Override
    public void deleteWorkOrder(String workOrderId) {
        workOrderStateMachine.deleteWorkOrder(workOrderId);
    }

    @Override
    public void closeWorkOrder(String workOrderId, String reason, String closer) {
        workOrderStateMachine.closeWorkOrder(workOrderId, reason, closer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder createWorkOrderWithDevices(WorkOrder order, List<WorkOrderDevice> devices) {
        this.save(order);
        bindDevices(order.getId(), devices);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder editWorkOrderWithDevices(WorkOrder updated, List<WorkOrderDevice> devices) {
        WorkOrder existing = this.getById(updated.getId());
        if (existing == null) {
            throw new IllegalArgumentException("工单不存在: " + updated.getId());
        }
        if (!"PENDING".equals(existing.getStatus())) {
            throw new IllegalStateException("仅允许编辑未开始的工单");
        }
        existing.setAgentId(updated.getAgentId());
        existing.setAgentName(updated.getAgentName());
        existing.setTaskName(updated.getTaskName());
        existing.setDepartmentId(updated.getDepartmentId());
        existing.setDepartmentName(updated.getDepartmentName());
        existing.setComplianceId(updated.getComplianceId());
        existing.setCurrency(updated.getCurrency());
        existing.setUnitPrice(updated.getUnitPrice());
        existing.setTotalPrice(updated.getTotalPrice());
        existing.setRemark(updated.getRemark());
        existing.setPlanStartTime(updated.getPlanStartTime());
        existing.setPlanEndTime(updated.getPlanEndTime());
        existing.setUpdateBy(updated.getUpdateBy());
        this.updateById(existing);
        bindDevices(existing.getId(), devices);
        return existing;
    }

    @Override
    public void pushStartedWorkOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkOrder> startedOrders = this.list(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getStatus, "STARTED")
                .eq(WorkOrder::getDelFlag, 0)
                .gt(WorkOrder::getPlanEndTime, now));
        if (startedOrders.isEmpty()) {
            return;
        }
        for (WorkOrder order : startedOrders) {
            WorkOrderDevice master = findMasterDevice(order.getId());
            if (master == null || master.getDeviceCode() == null) {
                log.warn("[pushStartedWorkOrders] 工单无主控设备，跳过: workOrderId={}", order.getId());
                continue;
            }
            try {
                String workOrderJson = objectMapper.writeValueAsString(order);
                deviceWebSocketServer.pushStartedWorkOrder(master.getDeviceCode(), workOrderJson);
            } catch (Exception e) {
                log.warn("[pushStartedWorkOrders] WebSocket 推送失败: workOrderId={}, err={}", order.getId(), e.getMessage());
            }
        }
        log.debug("[pushStartedWorkOrders] 推送进行中工单 {} 条", startedOrders.size());
    }

    @Override
    public IPage<WorkOrderOperationRecordDTO> pageWorkOrderOperationRecords(
            Page<WorkOrder> page,
            String controllerCode) {
        return workOrderMapper.pageWorkOrderOperationRecords(page, controllerCode);
    }

    private static final DateTimeFormatter DARWIN_DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder upsertWorkOrderFromDarwin(String tenant,
                                               WorkOrderCreateMsg.WorkOrderItem item,
                                               String traceId) {
        WorkOrderMapping mapping = darwinMappingMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderMapping>()
                        .eq(WorkOrderMapping::getDarwinOrderId, item.getId())
                        .eq(WorkOrderMapping::getDelFlag, 0));

        if (mapping != null) {
            // 已存在：更新工单字段
            return updateFromDarwin(mapping.getWorkOrderId(), tenant, item);
        } else {
            // 不存在：新建工单 + 映射
            return createFromDarwin(tenant, item);
        }
    }

    private WorkOrder createFromDarwin(String tenant, WorkOrderCreateMsg.WorkOrderItem item) {
        WorkOrderCreateMsg.CollectionPlan plan = item.getCollectionPlan();
        WorkOrder order = new WorkOrder();
        applyDarwinFields(order, tenant, item, plan);
        order.setStatus(WorkOrderConstant.ORDER_STATUS.PENDING);
        order.setSource(2);
        order.setCreateBy("darwin");
        order.setUpdateBy("darwin");
        this.save(order);

        WorkOrderMapping mapping = new WorkOrderMapping();
        mapping.setWorkOrderId(order.getId());
        mapping.setDarwinOrderId(item.getId());
        mapping.setCreateBy("darwin");
        mapping.setUpdateBy("darwin");
        try {
            mapping.setRawMessage(objectMapper.writeValueAsString(item));
        } catch (Exception ignored) {}
        darwinMappingMapper.insert(mapping);

        log.info("[Darwin] 工单创建完成 workOrderId={} darwinOrderId={}", order.getId(), item.getId());
        return order;
    }

    private WorkOrder updateFromDarwin(String workOrderId, String tenant,
                                       WorkOrderCreateMsg.WorkOrderItem item) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            log.warn("[Darwin] 工单不存在，降级为新建 workOrderId={} darwinOrderId={}", workOrderId, item.getId());
            return createFromDarwin(tenant, item);
        }
        WorkOrderCreateMsg.CollectionPlan plan = item.getCollectionPlan();
        applyDarwinFields(order, tenant, item, plan);
        order.setUpdateBy("darwin");
        this.updateById(order);

        log.info("[Darwin] 工单更新完成 workOrderId={} darwinOrderId={}", order.getId(), item.getId());
        return order;
    }

    /** 将 Darwin 消息字段写入 WorkOrder 对象（新建/更新共用） */
    private void applyDarwinFields(WorkOrder order, String tenant,
                                   WorkOrderCreateMsg.WorkOrderItem item,
                                   WorkOrderCreateMsg.CollectionPlan plan) {
        order.setTaskName(plan != null ? plan.getName() : null);
        order.setPlanStartTime(parseDateTime(plan != null ? plan.getBeginAt() : null));
        order.setPlanEndTime(parseDateTime(plan != null ? plan.getEndAt() : null));
        order.setTaskDesc(formatTaskDesc(item.getCollectionItem()));
        order.setQuotaTotal(parseQuota(item.getQuotaValue()));
        order.setTenantId(tenant);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkOrderFromDarwin(String darwinOrderId) {
        WorkOrderMapping mapping = darwinMappingMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderMapping>()
                        .eq(WorkOrderMapping::getDarwinOrderId, darwinOrderId)
                        .eq(WorkOrderMapping::getDelFlag, 0));
        if (mapping == null) {
            log.warn("[Darwin] 未找到映射记录，跳过删除 darwinOrderId={}", darwinOrderId);
            return;
        }
        // @TableLogic 软删除工单
        this.removeById(mapping.getWorkOrderId());
        // 软删除映射记录
        darwinMappingMapper.deleteById(mapping.getId());
        log.info("[Darwin] 工单删除完成 workOrderId={} darwinOrderId={}", mapping.getWorkOrderId(), darwinOrderId);
    }

    /** actions 格式化为 "1.xxx，2.xxx，3.xxx" */
    private static String formatTaskDesc(WorkOrderCreateMsg.CollectionItem item) {
        if (item == null || item.getActions() == null || item.getActions().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<WorkOrderCreateMsg.Action> actions = item.getActions();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append("，");
            sb.append(i + 1).append(".").append(actions.get(i).getName());
        }
        return sb.toString();
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, DARWIN_DT_FMT);
        } catch (Exception e) {
            log.warn("[Darwin] 日期时间解析失败 value={}", value);
            return null;
        }
    }

    private Integer parseQuota(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[Darwin] quotaValue 解析失败 value={}", value);
            return null;
        }
    }
}
