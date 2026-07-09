package org.jeecg.modules.ota.contract.api.fallback;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ota.contract.api.OtaFeignClient;
import org.jeecg.modules.ota.contract.dto.ActiveHighRiskTaskResult;

/**
 * OTA 服务不可用时的保守兜底：视为"存在进行中高风险任务"（阻止取消测试标记），
 * 而不是放行——PRD 反复强调"标记→升级→取消标记"防绕过是安全管控意图，宁可误拦
 * 也不可误放行。调用方应根据日志判断这是真实业务状态还是 OTA 不可达的兜底值。
 */
@Slf4j
public class OtaFeignFallback implements OtaFeignClient {

    @Setter
    private Throwable cause;

    @Override
    public Result<ActiveHighRiskTaskResult> getActiveHighRiskTask(String deviceId) {
        log.warn("[ota] OTA 平台不可用，取消测试标记前置校验按保守策略视为存在进行中高风险任务 deviceId={}", deviceId, cause);
        ActiveHighRiskTaskResult result = new ActiveHighRiskTaskResult();
        result.setHasActiveTask(true);
        return Result.ok(result);
    }
}
