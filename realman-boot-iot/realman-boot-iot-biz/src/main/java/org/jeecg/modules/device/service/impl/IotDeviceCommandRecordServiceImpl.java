package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDeviceCommandRecord;
import org.jeecg.modules.device.mapper.IotDeviceCommandRecordMapper;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class IotDeviceCommandRecordServiceImpl
        extends ServiceImpl<IotDeviceCommandRecordMapper, IotDeviceCommandRecord>
        implements IIotDeviceCommandRecordService {

    @Override
    @Async("deviceTaskExecutor")
    public void recordSend(String commandId, String deviceId, String deviceCode,
                           String commandType, String deviceType, String operator,
                           String paramsJson) {
        try {
            IotDeviceCommandRecord record = new IotDeviceCommandRecord();
            record.setCommandId(commandId);
            record.setDeviceId(deviceId);
            record.setDeviceCode(deviceCode);
            record.setCommandType(commandType);
            record.setDeviceType(deviceType);
            record.setStatus(DeviceConstant.CommandRecordStatus.PENDING);
            record.setOperator(operator);
            record.setParamsJson(paramsJson);
            record.setSendTime(LocalDateTime.now());
            save(record);
            log.debug("[CommandRecord] 记录指令下发 commandId={} deviceCode={} type={}", commandId, deviceCode, commandType);
        } catch (Exception e) {
            log.error("[CommandRecord] 记录指令下发失败 commandId={} deviceCode={}", commandId, deviceCode, e);
        }
    }

    @Override
    @Async("deviceTaskExecutor")
    public void ack(String commandId, boolean success, String failReason, String ackDataJson) {
        if (commandId == null || commandId.isEmpty()) {
            return;
        }
        try {
            String newStatus = success ? DeviceConstant.CommandRecordStatus.SUCCESS
                                       : DeviceConstant.CommandRecordStatus.FAIL;
            boolean updated = update(new LambdaUpdateWrapper<IotDeviceCommandRecord>()
                    .eq(IotDeviceCommandRecord::getCommandId, commandId)
                    .eq(IotDeviceCommandRecord::getStatus, DeviceConstant.CommandRecordStatus.PENDING)
                    .set(IotDeviceCommandRecord::getStatus, newStatus)
                    .set(!success && failReason != null, IotDeviceCommandRecord::getFailReason, failReason)
                    .set(ackDataJson != null, IotDeviceCommandRecord::getAckDataJson, ackDataJson)
                    .set(IotDeviceCommandRecord::getAckTime, LocalDateTime.now()));
            if (!updated) {
                log.warn("[CommandRecord] ACK 到达时指令已非 PENDING 状态，忽略更新 commandId={}", commandId);
            }
        } catch (Exception e) {
            log.error("[CommandRecord] 更新指令 ACK 失败 commandId={}", commandId, e);
        }
    }

    @Override
    public int markTimeout() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusSeconds(DeviceConstant.Timeout.COMMAND_ACK_TIMEOUT_SECONDS);
        return getBaseMapper().update(null, new LambdaUpdateWrapper<IotDeviceCommandRecord>()
                .eq(IotDeviceCommandRecord::getStatus, DeviceConstant.CommandRecordStatus.PENDING)
                .lt(IotDeviceCommandRecord::getSendTime, threshold)
                .set(IotDeviceCommandRecord::getStatus, DeviceConstant.CommandRecordStatus.TIMEOUT)
                .set(IotDeviceCommandRecord::getUpdateTime, LocalDateTime.now()));
    }
}
