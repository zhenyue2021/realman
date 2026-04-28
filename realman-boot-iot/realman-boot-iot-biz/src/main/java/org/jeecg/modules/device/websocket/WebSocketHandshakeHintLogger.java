package org.jeecg.modules.device.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动时打印 IoT 设备 WebSocket 期望路径，便于与 Nginx 透传 URI、Shiro 匹配路径对照排查。
 */
@Slf4j
@Component
public class WebSocketHandshakeHintLogger {

    private final Environment environment;

    public WebSocketHandshakeHintLogger(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logExpectedPath() {
        String cp = environment.getProperty("server.servlet.context-path", "/realman-iot");
        if (!cp.startsWith("/")) {
            cp = "/" + cp;
        }
        String base = cp.endsWith("/") ? cp.substring(0, cp.length() - 1) : cp;
        log.info(
                "[WebSocket] Device endpoint: ws://<host>:<port>{}/ws/device/{{deviceCode}} (path after context must be /ws/device/... )",
                base);
    }
}
