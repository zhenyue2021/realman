package org.jeecg.modules.device.service.signaling;

import org.jeecg.modules.device.config.WebRtcProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

/**
 * 信令 WebSocket 地址组装（serverIp 来自 turn_router，port 来自配置）。
 */
@Service
@RefreshScope
public class SignalingKeyService {

    private final WebRtcProperties webRtcProperties;

    public SignalingKeyService(WebRtcProperties webRtcProperties) {
        this.webRtcProperties = webRtcProperties;
    }

    /**
     * 返回信令 WebSocket URL（供 WebRTC 指令填充 signalUrl 字段）。
     */
    public String buildSignalUrl(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            return null;
        }
        int port = webRtcProperties.getSignaling().getServer().getPort();
        return "ws://" + serverIp + ":" + port;
    }
}
