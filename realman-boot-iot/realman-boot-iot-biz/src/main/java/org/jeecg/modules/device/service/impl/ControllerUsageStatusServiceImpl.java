package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.ControllerOperationRecord;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.mapper.ControllerOperationRecordMapper;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IControllerUsageStatusService;
import org.jeecg.modules.device.vo.UsageStatusVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ControllerUsageStatusServiceImpl implements IControllerUsageStatusService {

    private static final int DEVICE_TYPE_CONTROLLER = 2;
    private static final int DEVICE_TYPE_ROBOT = 1;

    private final IotDeviceMapper deviceMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final ControllerOperationRecordMapper operationRecordMapper;

    @Override
    public UsageStatusVO getUsageStatusByCode(String controllerCode) {
        if (controllerCode == null || controllerCode.isEmpty()) {
            return null;
        }
        IotDevice controller = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, controllerCode)
                        .eq(IotDevice::getDeviceType, DEVICE_TYPE_CONTROLLER));
        if (controller == null) {
            return null;
        }
        return buildUsageStatus(controller);
    }

    @Override
    public UsageStatusVO getUsageStatusById(String controllerId) {
        if (controllerId == null || controllerId.isEmpty()) {
            return null;
        }
        IotDevice controller = deviceMapper.selectById(controllerId);
        if (controller == null || !Integer.valueOf(DEVICE_TYPE_CONTROLLER).equals(controller.getDeviceType())) {
            return null;
        }
        return buildUsageStatus(controller);
    }

    private UsageStatusVO buildUsageStatus(IotDevice controller) {
        String cid = controller.getId();
        UsageStatusVO vo = new UsageStatusVO();
        vo.setControllerId(controller.getId());
        vo.setControllerCode(controller.getDeviceCode());
        vo.setLastLoginTime(controller.getLastLoginTime());

        // 最近一次遥操开始时间：该主控下操作记录中 start_time 最大值
        ControllerOperationRecord lastStart = operationRecordMapper.selectOne(
                new LambdaQueryWrapper<ControllerOperationRecord>()
                        .eq(ControllerOperationRecord::getControllerId, cid)
                        .orderByDesc(ControllerOperationRecord::getStartTime)
                        .last("LIMIT 1"));
        vo.setLastRemoteOperationStartTime(lastStart != null ? lastStart.getStartTime() : null);

        // 当前设备：该主控下 end_time 为 null 的记录（正在遥操的机器人），取最新一条
        ControllerOperationRecord currentOp = operationRecordMapper.selectOne(
                new LambdaQueryWrapper<ControllerOperationRecord>()
                        .eq(ControllerOperationRecord::getControllerId, cid)
                        .isNull(ControllerOperationRecord::getEndTime)
                        .orderByDesc(ControllerOperationRecord::getStartTime)
                        .last("LIMIT 1"));
        if (currentOp != null) {
            IotDevice robot = deviceMapper.selectById(currentOp.getRobotId());
            vo.setCurrentDevice(toRobotBasicVO(robot));
        } else {
            vo.setCurrentDevice(null);
        }

        // 可使用的机器人：iot_device_auth 中该主控绑定的机器人（status=1 且未逻辑删除）
        List<IotDeviceAuth> auths = deviceAuthMapper.selectList(
                new LambdaQueryWrapper<IotDeviceAuth>()
                        .eq(IotDeviceAuth::getControllerId, cid)
                        .eq(IotDeviceAuth::getStatus, 1));
        List<UsageStatusVO.RobotBasicVO> robots = new ArrayList<>();
        if (auths != null && !auths.isEmpty()) {
            List<String> robotIds = auths.stream().map(IotDeviceAuth::getDeviceId).distinct().collect(Collectors.toList());
            for (String rid : robotIds) {
                IotDevice d = deviceMapper.selectById(rid);
                if (d != null && Integer.valueOf(DEVICE_TYPE_ROBOT).equals(d.getDeviceType())) {
                    robots.add(toRobotBasicVO(d));
                }
            }
        }
        vo.setAvailableRobots(robots);
        return vo;
    }

    private static UsageStatusVO.RobotBasicVO toRobotBasicVO(IotDevice d) {
        if (d == null) return null;
        UsageStatusVO.RobotBasicVO vo = new UsageStatusVO.RobotBasicVO();
        vo.setRobotId(d.getId());
        vo.setRobotCode(d.getDeviceCode());
        vo.setRobotName(d.getDeviceName());
        vo.setStatus(d.getStatus());
        vo.setStatusText(statusText(d.getStatus()));
        vo.setDeviceModel(d.getDeviceModel());
        vo.setFirmwareVersion(d.getFirmwareVersion());
        vo.setBatteryLevel(null); // 可从 Redis/iot_device_status 扩展
        return vo;
    }

    private static String statusText(Integer status) {
        if (status == null) return "";
        switch (status) {
            case 0: return "未激活";
            case 1: return "运行中";
            case 2: return "离线";
            case 3: return "禁用";
            default: return String.valueOf(status);
        }
    }
}
