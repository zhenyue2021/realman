package org.jeecg.modules.device.service.webrtc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 房间绑定的 TURN/信令路由结果（Redis 缓存序列化）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomTurnRouteCache {

    private String serverIp;
    private int serverPort;
    private String signalKey;
}
