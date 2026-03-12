package org.jeecg.modules.device.service;

import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.modules.device.dto.ControllerLoginDTO;
import org.jeecg.modules.device.vo.TeleopLoginResolveVO;

/**
 * 主控登录解析服务：根据当前请求（含租户、IP 等）和登录信息，解析出主控与机器人。
 */
public interface IControllerLoginResolveService {

    /**
     * 登录后同步解析“当前主控 + 当前机器人 + 可用机器人列表”，并写入登录日志。
     *
     * <p>失败时抛出 RuntimeException，由全局异常处理器转换为 ApiResult.fail。
     */
    TeleopLoginResolveVO resolve(HttpServletRequest request, ControllerLoginDTO dto);
}

