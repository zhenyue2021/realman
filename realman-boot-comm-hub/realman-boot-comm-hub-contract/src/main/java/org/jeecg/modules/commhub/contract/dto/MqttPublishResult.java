package org.jeecg.modules.commhub.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
@Schema(description = "统一下行发布结果")
public class MqttPublishResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "PUBLISHED（已发布，未等待 ACK）/ ACKED（等到设备 ACK）/ TIMEOUT（等待超时）")
    private String status;

    @Schema(description = "设备侧 ACK 载荷，仅 status=ACKED 时返回")
    private Map<String, Object> ackPayload;
}
