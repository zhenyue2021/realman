package org.jeecg.modules.device.websocket;

import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.servlet.HandlerMapping;

import java.io.EOFException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Spring {@link org.springframework.web.socket.WebSocketHandler} 注册的设备长连接；由
 * {@code WebSocketHandlerMapping} 接管握手，优先于 DispatcherServlet 对剩余路径走静态资源的回退。
 * 会话仍使用 {@link DeviceWebSocketServer} 持有的 jakarta {@link Session} 与静态 {@code sessions} 表。
 */
@Slf4j
@Component
public class DeviceWebSocketChannelHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String deviceCode = resolveDeviceCode(session);
        if (deviceCode == null || deviceCode.isEmpty()) {
            log.error("[WS] 无法解析 deviceCode，关闭连接 uri={}", session.getUri());
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (Exception ignored) {
            }
            return;
        }
        Session nativeSession = unwrapNativeSession(session);
        if (nativeSession == null) {
            log.error("[WS] 无法取得 jakarta WebSocket Session，关闭连接 uri={}", session.getUri());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
            }
            return;
        }
        DeviceWebSocketServer.registerManagedSession(deviceCode, nativeSession);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String deviceCode = resolveDeviceCode(session);
        Session nativeSession = unwrapNativeSession(session);
        if (nativeSession != null) {
            DeviceWebSocketServer.removeManagedSession(deviceCode, nativeSession);
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        Throwable e = exception;
        if (!(e instanceof EOFException) && !(e.getCause() instanceof EOFException)) {
            log.error("[WS] 传输错误 sessionId={}", session.getId(), exception);
        } else {
            log.info("[WS] 客户端中断连接 sessionId={}", session.getId());
        }
        try {
            Session nativeSession = unwrapNativeSession(session);
            if (nativeSession != null) {
                DeviceWebSocketServer.removeManagedSession(resolveDeviceCode(session), nativeSession);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        // 当前业务以服务端推送为主，客户端下行若需处理可在此扩展
    }

    private static String resolveDeviceCode(WebSocketSession session) {
        Object raw = session.getAttributes().get(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (raw instanceof Map<?, ?> map && map.get("deviceCode") != null) {
            return Objects.toString(map.get("deviceCode"), "");
        }
        URI uri = session.getUri();
        if (uri == null) {
            return "";
        }
        String path = uri.getPath();
        String marker = "/ws/device/";
        int i = path.indexOf(marker);
        if (i < 0) {
            return "";
        }
        String tail = path.substring(i + marker.length());
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }

    private static Session unwrapNativeSession(WebSocketSession session) {
        if (session instanceof StandardWebSocketSession std) {
            Object nat = std.getNativeSession();
            if (nat instanceof Session s) {
                return s;
            }
        }
        return null;
    }
}
