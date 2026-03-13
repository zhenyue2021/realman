package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.modules.device.dto.MasterLoginDTO;
import org.jeecg.modules.device.entity.IotMasterLoginLog;
import org.jeecg.modules.device.vo.MasterLoginResolveVO;

/**
 * 主控端登录记录：操作员登录主控设备时记录登录信息及当时关联的机器人
 */
public interface IMasterLoginResolveService extends IService<IotMasterLoginLog> {

    /**
     * 记录主控端登录：写入登录记录表并更新主控设备的 last_login_time
     *
     * @param dto 主控ID/编码、操作员、关联机器人等
     * @throws IllegalArgumentException 主控设备不存在或非主控设备时
     */
    IotMasterLoginLog recordLogin(MasterLoginDTO dto);

    /**
     * 登录后同步解析“当前主控 + 当前机器人 + 可用机器人列表”，并写入登录日志。
     *
     * <p>失败时抛出 RuntimeException，由全局异常处理器转换为 ApiResult.fail。
     */
    MasterLoginResolveVO resolve(HttpServletRequest request, MasterLoginDTO dto);
}
