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
import org.jeecg.modules.device.dto.WorkOrderOperationRecordDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.service.workorder.IWorkOrderStateMachineService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderServiceImpl extends ServiceImpl<WorkOrderMapper, WorkOrder>
        implements IWorkOrderService {

    private final ObjectMapper objectMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final WorkOrderComplianceConfigMapper complianceConfigMapper;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final IWorkOrderStateMachineService workOrderStateMachine;

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
        String complianceId = existing.getComplianceId();
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
        // 根据complianceId判断当前工单合规配置是否有其他工单绑定，若无，则更新工单合规配置的使用状态
        updateComplianceApplyStatus(complianceId, updated.getComplianceId());
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

    /**
     * 更新合规配置的应用状态
     * <p>
     * 逻辑：
     * 1. 检查旧的合规配置（complianceId）是否还有其他工单在使用，如果没有则设置为未应用（0）
     * 2. 新的合规配置（newComplianceId）被当前工单使用，设置为已应用（1）
     *
     * @param oldComplianceId 旧的合规配置ID
     * @param newComplianceId 新的合规配置ID
     */
    private void updateComplianceApplyStatus(String oldComplianceId, String newComplianceId) {
        // 1. 检查旧合规配置是否还被其他工单使用
        if (oldComplianceId != null && !oldComplianceId.isEmpty()) {
            long count = this.count(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getComplianceId, oldComplianceId)
                    .eq(WorkOrder::getDelFlag, 0));
            
            // 如果没有其他工单使用该合规配置，则更新为未应用状态
            if (count == 0) {
                WorkOrderComplianceConfig oldConfig = complianceConfigMapper.selectById(oldComplianceId);
                if (oldConfig != null && oldConfig.getApplyStatus() != null && oldConfig.getApplyStatus() == 1) {
                    oldConfig.setApplyStatus(0);
                    complianceConfigMapper.updateById(oldConfig);
                    log.info("[updateComplianceApplyStatus] 合规配置 {} 无工单绑定，更新为未应用状态", oldComplianceId);
                }
            }
        }
        
        // 2. 将新合规配置标记为已应用
        if (newComplianceId != null && !newComplianceId.isEmpty()) {
            WorkOrderComplianceConfig newConfig = complianceConfigMapper.selectById(newComplianceId);
            if (newConfig != null && (newConfig.getApplyStatus() == null || newConfig.getApplyStatus() == 0)) {
                newConfig.setApplyStatus(1);
                complianceConfigMapper.updateById(newConfig);
                log.info("[updateComplianceApplyStatus] 合规配置 {} 被工单绑定，更新为已应用状态", newComplianceId);
            }
        }
    }
}
