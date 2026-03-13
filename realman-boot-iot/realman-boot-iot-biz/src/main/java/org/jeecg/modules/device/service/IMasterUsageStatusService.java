package org.jeecg.modules.device.service;

import org.jeecg.modules.device.vo.UsageStatusVO;

/**
 * 主控使用状态：最近登录时间、最近遥操开始时间、当前设备、可使用的机器人
 */
public interface IMasterUsageStatusService {

    /**
     * 按主控设备编号查询使用状态
     *
     * @param controllerCode 主控设备编号（device_code）
     * @return 使用状态 VO，主控不存在时返回 null
     */
    UsageStatusVO getUsageStatusByCode(String controllerCode);

    /**
     * 按主控设备ID查询使用状态
     *
     * @param controllerId 主控设备ID
     * @return 使用状态 VO，主控不存在时返回 null
     */
    UsageStatusVO getUsageStatusById(String controllerId);
}

