package org.jeecg.modules.ota.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ota.contract.dto.ActiveHighRiskTaskResult;
import org.jeecg.modules.ota.service.IOtaHighRiskTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对内查询接口，路径须与 {@link org.jeecg.modules.ota.contract.api.OtaFeignClient} 完全一致。
 * 供设备管理业务平台取消测试标记前置校验回调（OTA 平台详细设计第七章）。
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class HighRiskTaskController {

    private final IOtaHighRiskTaskService highRiskTaskService;

    @GetMapping("/internal/ota/devices/{deviceId}/active-high-risk-task")
    public Result<ActiveHighRiskTaskResult> getActiveHighRiskTask(@PathVariable String deviceId) {
        return Result.ok(highRiskTaskService.getActiveHighRiskTask(deviceId));
    }
}
