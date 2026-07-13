package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * HTTP-MQTT 桥接下行发布请求体，对应 {@code POST /api/v1/devices/{deviceId}/mqtt-bridge/publish}。
 * 与内部 {@code MqttPublishRequest} 字段一致，只是 deviceId 从路径参数取，不放在请求体里。
 * 见设备通信中台详细设计 4.3.1。
 */
@Data
@Schema(description = "HTTP-MQTT 桥接下行发布请求")
public class MqttBridgePublishRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "Topic 后缀，如 \"ota/notify\"、\"command/restart\"")
    private String topicSuffix;

    @Schema(description = "下行载荷，明文，通信中台按需做处理")
    private Object payload;

    @Schema(description = "是否加密下发，默认 true（本轮暂未接入应用层加密，见 MqttPublisher 类注释）")
    private boolean encrypt = true;

    @Schema(description = "QoS 等级，默认 1")
    private Integer qos = 1;

    @Schema(description = "是否等待设备侧 ACK，默认 false")
    private boolean waitAck = false;

    @Schema(description = "等待 ACK 的超时时间（毫秒），仅 waitAck=true 时生效")
    private Long ackTimeoutMs;

    @Schema(description = "ACK Topic 后缀，默认 bridge-ack；设备上报到 device/{deviceCode}/{ackTopicSuffix}")
    private String ackTopicSuffix;

    @Schema(description = "ACK JSON 中用于关联下行指令的字段名，默认 commandId")
    private String ackCommandIdField;
}
