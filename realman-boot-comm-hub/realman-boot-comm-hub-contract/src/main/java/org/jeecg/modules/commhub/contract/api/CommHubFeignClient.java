package org.jeecg.modules.commhub.contract.api;

import jakarta.validation.Valid;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.ServiceNameConstants;
import org.jeecg.modules.commhub.contract.api.fallback.CommHubFeignFallbackFactory;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 设备通信中台（{@code realman-comm-hub}）对内 Feign 契约：统一下行发布 API。
 *
 * <p>OTA / GLN / 数据处理向设备下发指令时统一调用本接口，不直连 EMQX；这与
 * {@code POST /api/v1/devices/{id}/mqtt-bridge/publish}（供第三方业务后台使用的
 * HTTP-MQTT 桥接，见设备通信中台详细设计 4.3.1）是同一份能力的两个入口，内部
 * 实现共用一套逻辑。
 */
@FeignClient(
        contextId = "commHubFeignClient",
        value = ServiceNameConstants.SERVICE_COMM_HUB,
        path = "${realman.comm-hub.context-path:/realman-comm-hub}",
        fallbackFactory = CommHubFeignFallbackFactory.class
)
public interface CommHubFeignClient {

    /**
     * 统一下行发布。{@code request.waitAck=false} 时立即返回 {@code PUBLISHED}；
     * {@code waitAck=true} 时同步等待设备 ACK（复用跨 Pod ACK 协调机制），超时返回
     * {@code TIMEOUT}。
     */
    @PostMapping("/internal/mqtt/publish")
    Result<MqttPublishResult> publish(@RequestBody @Valid MqttPublishRequest request);
}
