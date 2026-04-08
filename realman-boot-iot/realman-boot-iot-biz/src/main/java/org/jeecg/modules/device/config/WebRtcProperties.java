package org.jeecg.modules.device.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WebRTC 服务配置（TURN / STUN 服务器）
 *
 * <p>配置示例（application.yml / Nacos）：
 * <pre>
 * webrtc:
 *   turn-servers:
 *     - url: "192.168.1.100:3478"
 *       username: "user"
 *       password: "pass"
 *   stun-servers: "192.168.1.100:3478,192.168.1.101:3478"
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "webrtc")
public class WebRtcProperties {

    /** TURN 服务器列表，下发给机器人设备 */
    private List<TurnServer> turnServers = new ArrayList<>();

    /**
     * STUN 服务器列表（英文逗号分隔），下发给机器人设备
     *
     * <p>示例：{@code 192.168.1.100:3478,192.168.1.101:3478}
     */
    private String stunServers = "";

    /** 将逗号分隔的 stunServers 字符串拆分为 List */
    public List<String> getStunServerList() {
        if (stunServers == null || stunServers.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stunServers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** TURN 服务器配置 */
    @Data
    public static class TurnServer {
        /** TURN 服务器地址，例如 192.168.1.100:3478 */
        private String url;
        /** 用户名 */
        private String username;
        /** 密码 */
        private String password;
    }
}
