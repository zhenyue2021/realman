package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.commhub.mqtt.publisher.MqttPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code CommHubFeignClient} 契约实现：统一下行发布 API，内部调用方（OTA/GLN/数据处理）
 * 使用。与 {@link MqttBridgeController}（HTTP-MQTT 桥接，供第三方业务后台使用）共用
 * {@link MqttPublisher} 同一套实现，见设备通信中台详细设计 4.3.1。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "统一下行发布（内部）", description = "OTA/GLN/数据处理向设备下发指令的统一入口")
public class MqttPublishController {

    private final MqttPublisher mqttPublisher;

    @PostMapping("/internal/mqtt/publish")
    @Operation(summary = "统一下行发布（内部调用）")
    public Result<MqttPublishResult> publish(@RequestBody @Valid MqttPublishRequest request) {
        return Result.ok(mqttPublisher.publish(request));
    }
}
