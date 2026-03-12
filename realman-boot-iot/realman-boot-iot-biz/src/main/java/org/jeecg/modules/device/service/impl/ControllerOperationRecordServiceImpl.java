package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.ControllerOperationRecord;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.ControllerOperationRecordMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.service.IControllerOperationRecordService;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ControllerOperationRecordServiceImpl
        extends ServiceImpl<ControllerOperationRecordMapper, ControllerOperationRecord>
        implements IControllerOperationRecordService {

    private final WorkOrderDeviceMapper workOrderDeviceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createRecordsForWorkOrderStart(String workOrderId, String operatorId, String operatorName, LocalDateTime startTime) {
        List<WorkOrderDevice> devices = workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId));
        if (devices == null || devices.isEmpty()) {
            return;
        }
        String controllerId = null;
        String controllerCode = null;
        for (WorkOrderDevice d : devices) {
            if ("CONTROLLER".equalsIgnoreCase(d.getDeviceType())) {
                controllerId = d.getActualDeviceId() != null ? d.getActualDeviceId() : d.getDeviceId();
                controllerCode = d.getActualDeviceCode() != null ? d.getActualDeviceCode() : d.getDeviceCode();
                break;
            }
        }
        if (controllerId == null || controllerCode == null) {
            return;
        }
        for (WorkOrderDevice d : devices) {
            if (!"ROBOT".equalsIgnoreCase(d.getDeviceType())) {
                continue;
            }
            String robotId = d.getActualDeviceId() != null ? d.getActualDeviceId() : d.getDeviceId();
            String robotCode = d.getActualDeviceCode() != null ? d.getActualDeviceCode() : d.getDeviceCode();
            if (robotId == null || robotCode == null) {
                continue;
            }
            ControllerOperationRecord record = new ControllerOperationRecord();
            record.setId(IdUtil.fastSimpleUUID());
            record.setControllerId(controllerId);
            record.setControllerCode(controllerCode);
            record.setRobotId(robotId);
            record.setRobotCode(robotCode);
            record.setOperatorId(operatorId);
            record.setOperatorName(operatorName);
            record.setWorkOrderId(workOrderId);
            record.setStartTime(startTime);
            record.setEndTime(null);
            record.setCreateTime(LocalDateTime.now());
            save(record);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishByWorkOrder(String workOrderId, LocalDateTime endTime) {
        if (workOrderId == null || endTime == null) {
            return;
        }
        update(new LambdaUpdateWrapper<ControllerOperationRecord>()
                .eq(ControllerOperationRecord::getWorkOrderId, workOrderId)
                .isNull(ControllerOperationRecord::getEndTime)
                .set(ControllerOperationRecord::getEndTime, endTime)
                .set(ControllerOperationRecord::getUpdateTime, LocalDateTime.now()));
    }

    @Override
    public IPage<ControllerOperationRecord> pageRecords(Page<ControllerOperationRecord> page,
                                                         String controllerId, String controllerCode,
                                                         String robotId, LocalDateTime startTimeFrom, LocalDateTime startTimeTo) {
        LambdaQueryWrapper<ControllerOperationRecord> w = new LambdaQueryWrapper<>();
        if (controllerId != null && !controllerId.isEmpty()) {
            w.eq(ControllerOperationRecord::getControllerId, controllerId);
        }
        if (controllerCode != null && !controllerCode.isEmpty()) {
            w.eq(ControllerOperationRecord::getControllerCode, controllerCode);
        }
        if (robotId != null && !robotId.isEmpty()) {
            w.eq(ControllerOperationRecord::getRobotId, robotId);
        }
        if (startTimeFrom != null) {
            w.ge(ControllerOperationRecord::getStartTime, startTimeFrom);
        }
        if (startTimeTo != null) {
            w.le(ControllerOperationRecord::getStartTime, startTimeTo);
        }
        w.orderByDesc(ControllerOperationRecord::getStartTime);
        return page(page, w);
    }

    @Override
    public List<ControllerOperationRecord> listForExport(String controllerId, String controllerCode,
                                                         String robotId, LocalDateTime startTimeFrom, LocalDateTime startTimeTo) {
        LambdaQueryWrapper<ControllerOperationRecord> w = new LambdaQueryWrapper<>();
        if (controllerId != null && !controllerId.isEmpty()) {
            w.eq(ControllerOperationRecord::getControllerId, controllerId);
        }
        if (controllerCode != null && !controllerCode.isEmpty()) {
            w.eq(ControllerOperationRecord::getControllerCode, controllerCode);
        }
        if (robotId != null && !robotId.isEmpty()) {
            w.eq(ControllerOperationRecord::getRobotId, robotId);
        }
        if (startTimeFrom != null) {
            w.ge(ControllerOperationRecord::getStartTime, startTimeFrom);
        }
        if (startTimeTo != null) {
            w.le(ControllerOperationRecord::getStartTime, startTimeTo);
        }
        w.orderByDesc(ControllerOperationRecord::getStartTime);
        return list(w);
    }
}
