package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Builder;
import lombok.Data;

/** Teleop → Darwin（RocketMQ）：转发机器人 OSS 授权请求 */
@Data
@Builder
public class OssAuthRequestMsg {

    private String tenant;
    private String deviceCode;
    private String traceId;
    private long eventTime;
    private MsgData data;
    private String requestId;

    @Data
    @Builder
    public static class MsgData {
        private String requestId;
        private String taskId;
    }
}
