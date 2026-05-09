package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeviceStatusMsg {

    private String traceId;
    private String deviceCode;
    /** MASTER / SLAVE */
    private String deviceType;
    /** ONLINE / OFFLINE */
    private String eventType;
    private Long eventTime;
    /** 下线原因，上线时为空字符串 */
    private String offlineReason;
}
