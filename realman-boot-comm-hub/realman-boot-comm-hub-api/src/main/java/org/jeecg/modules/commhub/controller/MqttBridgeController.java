package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.commhub.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.commhub.vo.MqttBridgePublishRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-MQTT 桥接：供第三方业务后台（如 SmartArm）以 HTTP 语义间接完成 MQTT 下行发布，
 * 设备侧协议不变（仍是 MQTT），见设备通信中台详细设计 4.3.1。物理上经 WEB 端向网关
 * 统一入口/鉴权/限流后到达本控制器（本控制器只负责业务逻辑）。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "HTTP-MQTT 桥接", description = "第三方业务后台通过 HTTP 语义下发 MQTT 指令")
public class MqttBridgeController {

    private final MqttPublisher mqttPublisher;

    @PostMapping("/api/v1/devices/{deviceId}/mqtt-bridge/publish")
    @Operation(summary = "HTTP-MQTT 桥接下行发布")
    public Result<MqttPublishResult> publish(@PathVariable String deviceId,
                                              @RequestBody @Valid MqttBridgePublishRequest request) {
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
