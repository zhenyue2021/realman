package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.dto.ControllerLoginDTO;
import org.jeecg.modules.device.entity.IotControllerLoginLog;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotControllerLoginLogMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IControllerLoginLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 主控端登录记录：写入登录日志并更新主控设备 last_login_time
 */
@Service
@RequiredArgsConstructor
public class ControllerLoginLogServiceImpl extends ServiceImpl<IotControllerLoginLogMapper, IotControllerLoginLog>
        implements IControllerLoginLogService {

    private final IotDeviceMapper deviceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotControllerLoginLog recordLogin(ControllerLoginDTO dto) {
        if ((dto.getControllerId() == null || dto.getControllerId().isEmpty())
                && (dto.getControllerCode() == null || dto.getControllerCode().isEmpty())) {
            throw new IllegalArgumentException("主控设备ID或设备编码不能为空");
        }

        IotDevice controller = null;
        if (dto.getControllerId() != null && !dto.getControllerId().isEmpty()) {
            controller = deviceMapper.selectById(dto.getControllerId());
        }
        if (controller == null && dto.getControllerCode() != null && !dto.getControllerCode().isEmpty()) {
            controller = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getDeviceCode, dto.getControllerCode()));
        }
        if (controller == null) {
            throw new IllegalArgumentException("主控设备不存在");
        }
        if (controller.getDeviceType() == null || controller.getDeviceType() != 2) {
            throw new IllegalArgumentException("该设备不是主控设备，无法记录主控登录");
        }

        LocalDateTime now = LocalDateTime.now();
        IotControllerLoginLog log = new IotControllerLoginLog();
        log.setControllerId(controller.getId());
        log.setControllerCode(controller.getDeviceCode());
        log.setOperatorId(dto.getOperatorId());
        log.setOperatorName(dto.getOperatorName());
        log.setAssociatedRobotId(dto.getAssociatedRobotId());
        log.setAssociatedRobotCode(dto.getAssociatedRobotCode());
        log.setLoginTime(now);
        log.setCreateTime(now);
        save(log);

        controller.setLastLoginTime(now);
        deviceMapper.updateById(controller);
        return log;
    }
}
