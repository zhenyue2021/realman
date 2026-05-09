package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Builder;
import lombok.Data;

/** Teleop → Darwin（RocketMQ）：转发机器人 OSS 授权请求 */
@Data
@Builder
public class OssAuthRequestMsg {
    private String traceId;
    private String requestId;
    private String deviceCode;
    private String taskId;
    private long timestamp;
}
