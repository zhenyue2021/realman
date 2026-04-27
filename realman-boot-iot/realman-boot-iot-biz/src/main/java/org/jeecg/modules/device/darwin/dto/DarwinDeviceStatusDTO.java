package org.jeecg.modules.device.darwin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DarwinDeviceStatusDTO {

    private String traceId;
    private String deviceCode;
    /** MASTER / SLAVE */
    private String deviceType;
    /** ONLINE / OFFLINE */
    private String eventType;
    private LocalDateTime eventTime;
    /** 下线原因，上线时为空字符串 */
    private String offlineReason;
}
