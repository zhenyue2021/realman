package org.jeecg.modules.device.config;

import org.jeecg.modules.device.websocket.DeviceWebSocketChannelHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring WebSocket 映射：路径相对 {@code server.servlet.context-path}，即全路径为 {@code /realman-iot/ws/device/{deviceCode}}。
 */
@Configuration
@EnableWebSocket
public class DeviceWebSocketMvcConfig implements WebSocketConfigurer {

    private final DeviceWebSocketChannelHandler deviceWebSocketChannelHandler;

    public DeviceWebSocketMvcConfig(DeviceWebSocketChannelHandler deviceWebSocketChannelHandler) {
        this.deviceWebSocketChannelHandler = deviceWebSocketChannelHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceWebSocketChannelHandler, "/ws/device/{deviceCode}")
                .setAllowedOriginPatterns("*");
    }
}
