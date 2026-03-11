package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderServiceImpl extends ServiceImpl<WorkOrderMapper, WorkOrder>
        implements IWorkOrderService {

    private final WorkOrderDeviceMapper workOrderDeviceMapper;

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
        if (controllerCode == null || controllerCode.isEmpty()) {
            return List.of();
        }
        // 先查找与该主控相关的工单ID（计划主控或实际主控）
        LambdaQueryWrapper<WorkOrderDevice> deviceWrapper = new LambdaQueryWrapper<WorkOrderDevice>()
                .eq(WorkOrderDevice::getDeviceType, "CONTROLLER")
                .eq(WorkOrderDevice::getDeviceCode, controllerCode)
                .and(w -> w.isNull(WorkOrderDevice::getActualDeviceCode)
                        .or().eq(WorkOrderDevice::getActualDeviceCode, controllerCode));
        List<WorkOrderDevice> binds = workOrderDeviceMapper.selectList(deviceWrapper);
        if (binds.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = binds.stream()
                .map(WorkOrderDevice::getWorkOrderId)
                .distinct()
                .toList();

        LocalDateTime now = LocalDateTime.now();
        // 返回当前时间窗口内且未开始的工单，按计划开始时间升序
        return this.baseMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getId, orderIds)
                .eq(WorkOrder::getStatus, "PENDING")
                .eq(WorkOrder::getDelFlag, 0)
                .le(WorkOrder::getPlanStartTime, now)
                .gt(WorkOrder::getPlanEndTime, now)
                .orderByAsc(WorkOrder::getPlanStartTime));
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
    @Transactional(rollbackFor = Exception.class)
    public void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitWorkOrder(String workOrderId) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        // 仅允许已开始或已超时的工单提交
        if (!"STARTED".equals(order.getStatus()) && !"TIMEOUT".equals(order.getStatus())) {
            throw new IllegalStateException("当前工单状态不允许提交");
        }
        order.setStatus("SUBMITTED");
        order.setSubmitTime(LocalDateTime.now());
        this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fillTimeoutReason(String workOrderId, String reason, String source) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        order.setTimeoutReason(reason);
        order.setTimeoutReasonSource(source != null && !source.isEmpty() ? source : "USER");
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
    }
}

