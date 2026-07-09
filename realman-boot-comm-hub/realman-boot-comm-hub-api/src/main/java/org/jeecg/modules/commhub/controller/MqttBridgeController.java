package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.commhub.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.commhub.service.BridgeRateLimitService;
import org.jeecg.modules.commhub.service.IApiKeyService;
import org.jeecg.modules.commhub.vo.MqttBridgePublishRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-MQTT 桥接：供第三方业务后台（如 SmartArm）以 HTTP 语义间接完成 MQTT 下行发布，
 * 设备侧协议不变（仍是 MQTT），见设备通信中台详细设计 4.3.1。调用方须携带
 * {@code X-Api-Key}，由 {@link IApiKeyService} 校验设备/Topic 授权范围（防止越权向
 * 不属于自己的设备下发指令），并按 Key 维度限流（4.5）。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "HTTP-MQTT 桥接", description = "第三方业务后台通过 HTTP 语义下发 MQTT 指令")
public class MqttBridgeController {

    private static final String HEADER_API_KEY = "X-Api-Key";
    /** 每个 API Key 每分钟最多下发次数，见设备通信中台详细设计 4.5"限流与幂等"。 */
    private static final int RATE_LIMIT_PER_MINUTE = 60;

    private final MqttPublisher mqttPublisher;
    private final IApiKeyService apiKeyService;
    private final BridgeRateLimitService bridgeRateLimitService;

    @PostMapping("/api/v1/devices/{deviceId}/mqtt-bridge/publish")
    @Operation(summary = "HTTP-MQTT 桥接下行发布（需 X-Api-Key，按设备/Topic 授权范围校验 + 按 Key 限流）")
    public Result<MqttPublishResult> publish(@PathVariable String deviceId,
                                              @RequestHeader(HEADER_API_KEY) String apiKey,
                                              @RequestBody @Valid MqttBridgePublishRequest request) {
        String apiKeyId = apiKeyService.assertAuthorized(apiKey, deviceId, request.getTopicSuffix());
        if (bridgeRateLimitService.isExceeded(apiKeyId, RATE_LIMIT_PER_MINUTE)) {
            throw new JeecgBootBizTipException("ERR_BRIDGE_RATE_LIMIT");
        }

        MqttPublishRequest internal = new MqttPublishRequest();
        internal.setDeviceId(deviceId);
        internal.setTopicSuffix(request.getTopicSuffix());
        internal.setPayload(request.getPayload());
        internal.setEncrypt(request.isEncrypt());
        internal.setQos(request.getQos());
        internal.setWaitAck(request.isWaitAck());
        internal.setAckTimeoutMs(request.getAckTimeoutMs());
        return Result.ok(mqttPublisher.publish(internal));
    }
}
