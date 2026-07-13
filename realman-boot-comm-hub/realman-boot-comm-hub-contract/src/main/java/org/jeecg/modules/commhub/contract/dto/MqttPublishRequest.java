package org.jeecg.modules.commhub.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一下行发布请求。对应 {@code MqttPublisher} 的统一下行发布 API，也是
 * {@code POST /api/v1/devices/{id}/mqtt-bridge/publish}（HTTP-MQTT 下行桥接，
 * 见设备通信中台详细设计 4.3.1）的内部实现载体。业务应用（OTA/GLN/数据处理）与
 * 北向网关共用同一个请求结构，不需要为"内部调用"和"桥接调用"各定义一套 DTO。
 */
@Data
@Schema(description = "统一下行发布请求")
public class MqttPublishRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "内部设备 ID 或设备码，任选其一，由通信中台解析出目标 MQTT Topic")
    private String deviceId;

    @NotBlank
    @Schema(description = "Topic 后缀，如 \"ota/notify\"、\"command/restart\"；实际发布到 device/{code}/{topicSuffix}")
    private String topicSuffix;

    @Schema(description = "下行载荷，明文，通信中台按需做 AES 加密")
    private Object payload;

    @Schema(description = "是否加密下发，默认 true")
    private boolean encrypt = true;

    @Schema(description = "QoS 等级，默认 1")
    private Integer qos = 1;

    @Schema(description = "是否等待设备侧 ACK；true 时走 publish-and-wait，见详细设计 4.3.1")
    private boolean waitAck = false;

    @Schema(description = "等待 ACK 的超时时间（毫秒），仅 waitAck=true 时生效")
    private Long ackTimeoutMs;

    @Schema(description = "ACK Topic 后缀，默认 bridge-ack；实际订阅/上报为 device/{code}/{ackTopicSuffix}")
    private String ackTopicSuffix;

    @Schema(description = "ACK JSON 中用于关联下行指令的字段名，默认 commandId")
    private String ackCommandIdField;
}
