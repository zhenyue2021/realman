package org.jeecg.modules.ota.service;

import org.jeecg.modules.ota.contract.dto.ActiveHighRiskTaskResult;

/**
 * 供设备管理业务平台取消测试标记前置校验回调，见 OTA 平台详细设计第七章、
 * {@code OtaFeignClient#getActiveHighRiskTask}。
 */
public interface IOtaHighRiskTaskService {

    ActiveHighRiskTaskResult getActiveHighRiskTask(String deviceId);
}
