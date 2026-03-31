package org.jeecg.modules.device.service.impl.workorder;

import cn.hutool.core.collection.CollectionUtil;
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
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
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
    private final IMasterOperationRecordService operationRecordService;
    private final DeviceWebSocketServer deviceWebSocketServer;

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
        // 先查找与该主控相关的工单ID（计划主控或实际主控）
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

        // 返回进行中（STARTED）和待开始（PENDING）的工单，按计划开始时间升序 ..此处不查询是否超时，超时逻辑已在定时任务中处理
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

    public WorkOrderDevice findMasterDevice(String workOrderId) {
        return workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId).eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER).last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        // 获取主控设备信息
        WorkOrderDevice master = findMasterDevice(workOrderId);
        // 仅允许从 PENDING 开始，且当前时间在计划窗口内
        LocalDateTime now = LocalDateTime.now();
        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("当前工单状态不允许开始");
        }
        if (order.getPlanStartTime() != null && now.isBefore(order.getPlanStartTime())) {
            throw new IllegalStateException("尚未到工单计划开始时间");
        }
        if (order.getPlanEndTime() != null && !now.isBefore(order.getPlanEndTime())) {
            throw new IllegalStateException("工单已过计划结束时间");
        }
        order.setStatus("STARTED");
        order.setOperatorId(operatorId);
        order.setOperatorName(operatorName);
        order.setOperatorPhone(operatorPhone);
        order.setActualStartTime(now);
        this.updateById(order);
        operationRecordService.createRecordsForWorkOrderStart(workOrderId, operatorId, operatorName, now);
        // 更新实际使用设备-即当前设置的主控及机器人设备
        List<WorkOrderDevice> devices = findDevices(workOrderId);
        if (CollectionUtil.isNotEmpty(devices)) {
            devices.forEach(d -> {
                d.setActualDeviceId(d.getDeviceId());
                d.setActualDeviceName(d.getDeviceName());
                d.setActualDeviceCode(d.getDeviceCode());
                workOrderDeviceMapper.updateById(d);
            });
        }
        // 通过 WebSocket 推送工单信息给前端
        try {
            String workOrderJson = objectMapper.writeValueAsString(order);
            deviceWebSocketServer.pushStartedWorkOrder(master.getDeviceCode(), workOrderJson);
        } catch (Exception e) {
            log.warn("[startWorkOrder] WebSocket 推送工单失败: workOrderId={}, err={}", order.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitWorkOrder(String workOrderId, String operator) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        // 仅允许已开始或未超时的工单提交
        if (!"STARTED".equals(order.getStatus()) && !"TIMEOUT".equals(order.getStatus())) {
            throw new IllegalStateException("当前工单状态不允许提交---" + order.getStatus());
        }
        // 仅允许提交自己开启的工单
        if (!operator.equals(order.getOperatorName())) {
            throw new IllegalStateException("该工单不是由您开启，您无法提交他人工单");
        }
        order.setUpdateBy(operator);
        order.setStatus("SUBMITTED");
        LocalDateTime submitTime = LocalDateTime.now();
        order.setSubmitTime(submitTime);
        this.updateById(order);
        operationRecordService.finishByWorkOrder(workOrderId, submitTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fillTimeoutReason(String workOrderId, String reason, String source) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        order.setTimeoutReason(reason);
        order.setTimeoutReasonSource(source != null && !source.isEmpty() ? source : "用户原因");
        this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditWorkOrder(String workOrderId, String result, String comment, String auditor) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        // 仅允许对 SUBMITTED 或 TIMEOUT 的工单审核
        if (!"SUBMITTED".equals(order.getStatus()) && !"TIMEOUT".equals(order.getStatus())) {
            throw new IllegalStateException("当前工单状态不允许审核");
        }
        order.setAuditResult(result);
        order.setAuditComment(comment);
        order.setAuditBy(auditor);
        order.setAuditTime(LocalDateTime.now());
        order.setStatus("COMPLETED");
        this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkOrder(String workOrderId) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            throw new IllegalArgumentException("工单不存在: " + workOrderId);
        }
        if (!WorkOrderConstant.ORDER_STATUS.PENDING.equals(order.getStatus())) {
            throw new IllegalStateException("仅待开始（PENDING）状态的工单可删除，当前状态: " + order.getStatus());
        }
        this.removeById(workOrderId);
        log.info("[WorkOrder] 工单已逻辑删除: workOrderId={}", workOrderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeWorkOrder(String workOrderId, String reason, String closer) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        // 若在 TIMEOUT 且尚未填写超时原因时关闭，则补齐系统默认原因
        if ("TIMEOUT".equals(order.getStatus()) && (order.getTimeoutReason() == null || order.getTimeoutReason().isEmpty())) {
            order.setTimeoutReasonSource("SYSTEM");
            order.setTimeoutReason(reason != null && !reason.isEmpty() ? reason : "用户原因");
        }
        order.setStatus("CLOSED");
        order.setCloseReason(reason);
        order.setCloseBy(closer);
        order.setCloseTime(LocalDateTime.now());
        this.updateById(order);
        // 关闭时结束操作时间 = 工单失效时间
        if (order.getPlanEndTime() != null) {
            operationRecordService.finishByWorkOrder(workOrderId, order.getPlanEndTime());
        }
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
        // 查询所有已开启且未超时的工单
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
}

