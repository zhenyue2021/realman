package org.jeecg.modules.ota.contract.api;

import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.ServiceNameConstants;
import org.jeecg.modules.ota.contract.api.fallback.OtaFeignFallbackFactory;
import org.jeecg.modules.ota.contract.dto.ActiveHighRiskTaskResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * OTA 平台（{@code realman-ota}）对内 Feign 契约。
 *
 * <p>OTA 平台不重新实现设备注册/Token/测试标记（已在设备基座 {@code realman-device-mgmt}
 * 落地，见 OTA 平台详细设计第七章），本契约只暴露"是否存在进行中高风险任务"这一个
 * 内部只读查询，供设备管理业务平台取消测试标记前置校验回调使用。
 *
 * <p>本模块目前只有 contract，尚无 biz 实现（Phase 0）；{@code @ConditionalOnMissingClass}
 * 用于后续 Phase 3 落地 biz 实现类后自动让位，避免同进程内重复实例化。
 */
@FeignClient(
        contextId = "otaFeignClient",
        value = ServiceNameConstants.SERVICE_OTA,
        path = "${realman.ota.context-path:/realman-ota}",
        fallbackFactory = OtaFeignFallbackFactory.class
)
public interface OtaFeignClient {

    /**
     * 查询设备是否存在进行中的 high_risk 升级任务。调用方：设备管理业务平台
     * （取消 is_test_device 标记前的前置校验，见设备基座详细设计 3.5）。
     */
    @GetMapping("/internal/ota/devices/{deviceId}/active-high-risk-task")
    Result<ActiveHighRiskTaskResult> getActiveHighRiskTask(@PathVariable("deviceId") String deviceId);
}
