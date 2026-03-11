package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.dto.ControllerLoginDTO;
import org.jeecg.modules.device.entity.IotControllerLoginLog;

/**
 * 主控端登录记录：操作员登录主控设备时记录登录信息及当时关联的机器人
 */
public interface IControllerLoginLogService extends IService<IotControllerLoginLog> {

    /**
     * 记录主控端登录：写入登录记录表并更新主控设备的 last_login_time
     *
     * @param dto 主控ID/编码、操作员、关联机器人等
     * @throws IllegalArgumentException 主控设备不存在或非主控设备时
     */
    void recordLogin(ControllerLoginDTO dto);
}
