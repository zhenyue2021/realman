package org.jeecg.modules.ota.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.ota.contract.dto.ActiveHighRiskTaskResult;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.enums.NonTerminalStates;
import org.jeecg.modules.ota.mapper.OtaFirmwareMapper;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaTaskMapper;
import org.jeecg.modules.ota.service.IOtaHighRiskTaskService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 实现见 {@link IOtaHighRiskTaskService}：设备是否存在进行中的 high_risk 任务，
 * 判定依据是该设备当前非终态子任务所属固件包的 risk_level。
 */
@Service
@RequiredArgsConstructor
public class OtaHighRiskTaskServiceImpl implements IOtaHighRiskTaskService {

    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final OtaTaskMapper taskMapper;
    private final OtaFirmwareMapper firmwareMapper;

    @Override
    public ActiveHighRiskTaskResult getActiveHighRiskTask(String deviceId) {
        ActiveHighRiskTaskResult result = new ActiveHighRiskTaskResult();
        List<OtaTaskDevice> subTasks = taskDeviceMapper.selectList(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getDeviceId, deviceId)
                .notIn(OtaTaskDevice::getState, NonTerminalStates.terminalStates()));
        for (OtaTaskDevice subTask : subTasks) {
            org.jeecg.modules.ota.entity.OtaTask task = taskMapper.selectById(subTask.getTaskId());
            if (task == null) {
                continue;
            }
            OtaFirmware firmware = firmwareMapper.selectById(task.getPackageId());
            if (firmware != null && "high_risk".equalsIgnoreCase(firmware.getRiskLevel())) {
                result.setHasActiveTask(true);
                result.setTaskId(task.getTaskId());
                return result;
            }
        }
        result.setHasActiveTask(false);
        return result;
    }
}
