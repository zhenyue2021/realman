package org.jeecg.modules.device.service.webrtc;

import lombok.Builder;
import lombok.Data;

/**
 * turn_router 调度 API 成功响应。
 */
@Data
@Builder
public class TurnRouteResult {

    private String serverId;
    private String serverIp;
    private int serverPort;
    private String serverName;
}
