package org.jeecg.modules.device.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * WebRTC 服务配置（TURN 凭证、信令端口、turn_router 调度地址）。
 *
 * <p>配置示例（application.yml / Nacos）：
 * <pre>
 * webrtc:
 *   turn-server:
 *     username: "realman"
 *     password: "pass"
 *   signaling:
 *     server:
 *       port: 8091
 *   turn-router:
 *     base-url: "http://8.141.21.23:8081"
 *     connect-timeout-ms: 5000
 *     read-timeout-ms: 5000
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "webrtc")
@RefreshScope
public class WebRtcProperties {

    /** TURN 认证凭证（地址由 turn_router 动态返回） */
    private TurnServer turnServer = new TurnServer();

    private Signaling signaling = new Signaling();

    private TurnRouter turnRouter = new TurnRouter();

    @Data
    public static class TurnServer {
        private String username;
        private String password;
    }

    @Data
    public static class Signaling {
        private Server server = new Server();

        @Data
        public static class Server {
            /** 信令 WebSocket 端口，serverIp 来自 turn_router */
            private int port = 8091;
        }
    }

    @Data
    public static class TurnRouter {
        private String baseUrl = "";
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 5_000;
    }
}
