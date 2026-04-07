package org.jeecg.modules.device.service.impl.workorder;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.WorkOrderConstant;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.service.workorder.IWorkOrderStateMachineService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单状态机实现：开启、提交、审核、关闭、补填超时原因、删除等状态迁移与不变式校验。
 */
@Slf4j
@Service
public class WorkOrderStateMachineServiceImpl extends ServiceImpl<WorkOrderMapper, WorkOrder>
        implements IWorkOrderStateMachineService {

    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final IMasterOperationRecordService operationRecordService;

    public WorkOrderStateMachineServiceImpl(WorkOrderMapper workOrderMapper,
                                            WorkOrderDeviceMapper workOrderDeviceMapper,
                                            IMasterOperationRecordService operationRecordService) {
        this.baseMapper = workOrderMapper;
        this.workOrderDeviceMapper = workOrderDeviceMapper;
        this.operationRecordService = operationRecordService;
    }


    private List<WorkOrderDevice> findDevices(String workOrderId) {
        return workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startWorkOrder(String workOrderId, String operatorId, String operatorName, String operatorPhone) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
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
        List<WorkOrderDevice> devices = findDevices(workOrderId);
        if (CollectionUtil.isNotEmpty(devices)) {
            devices.forEach(d -> {
                d.setActualDeviceId(d.getDeviceId());
                d.setActualDeviceName(d.getDeviceName());
                d.setActualDeviceCode(d.getDeviceCode());
                workOrderDeviceMapper.updateById(d);
            });
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitWorkOrder(String workOrderId, String operator) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            return;
        }
        if (!"STARTED".equals(order.getStatus()) && !"TIMEOUT".equals(order.getStatus())) {
            throw new IllegalStateException("当前工单状态不允许提交---" + order.getStatus());
        }
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
        if ("TIMEOUT".equals(order.getStatus()) && (order.getTimeoutReason() == null || order.getTimeoutReason().isEmpty())) {
            order.setTimeoutReasonSource("SYSTEM");
            order.setTimeoutReason(reason != null && !reason.isEmpty() ? reason : "用户原因");
        }
        order.setStatus("CLOSED");
        order.setCloseReason(reason);
        order.setCloseBy(closer);
        order.setCloseTime(LocalDateTime.now());
        this.updateById(order);
        if (order.getPlanEndTime() != null) {
            operationRecordService.finishByWorkOrder(workOrderId, order.getPlanEndTime());
        }
    }
}
