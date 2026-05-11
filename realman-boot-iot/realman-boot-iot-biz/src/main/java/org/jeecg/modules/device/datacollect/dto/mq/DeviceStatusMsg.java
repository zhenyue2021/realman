package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeviceStatusMsg {

    private String tenant;
    private String deviceCode;
    private String traceId;
    private Long eventTime;
    private MsgData data;

    @Data
    @Builder
    public static class MsgData {
        /** MASTER / SLAVE */
        private String deviceType;
        /** ONLINE / OFFLINE */
        private String eventType;
        /** 下线原因，上线时为空字符串 */
        private String offlineReason;
    }
}
