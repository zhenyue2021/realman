package org.jeecg.modules.device.websocket;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时推送服务
 * ws://host/device-mgmt/ws/device/{deviceCode}
 * ws://host/device-mgmt/ws/device/all  (全局上下线)
 */
@Slf4j
@Component
@ServerEndpoint("/ws/device/{deviceCode}")
public class DeviceWebSocketServer {

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("deviceCode") String deviceCode) {
        sessions.put(deviceCode + ":" + session.getId(), session);
        log.info("[WS] 客户端连接 deviceCode={}, sessionId={}", deviceCode, session.getId());
    }

    @OnClose
    public void onClose(Session session, @PathParam("deviceCode") String deviceCode) {
        sessions.remove(deviceCode + ":" + session.getId());
    }

    @OnError
    public void onError(Session session, Throwable e) {
        log.error("[WS] 错误 sessionId={}", session.getId(), e);
    }

    /** 推送设备实时状态 */
    public void pushDeviceStatus(String deviceCode, String statusJson) {
        String msg = "{type:STATUS,deviceCode:" + deviceCode + ",data:" + statusJson + "}";
        send(deviceCode, msg);
        send("all", msg);
    }

    /** 推送设备上下线事件 */
    public void pushDeviceOnlineStatus(String deviceCode, boolean online) {
        String msg = "{type:ONLINE_STATUS,deviceCode:" + deviceCode
                + ",online:" + online + "ts:" + System.currentTimeMillis() + "}";
        send(deviceCode, msg);
        send("all", msg);
    }

    /** 推送OTA进度 */
    public void pushOtaProgress(String deviceCode, String taskId, Integer status, Integer progress) {
        String msg = "{type:OTA_PROGRESS,deviceCode:" + deviceCode
                + ",taskId:"+ taskId + ",status:" + status
                + ",progress:" + progress + "}";
        send(deviceCode, msg);
    }

    private void send(String deviceCode, String message) {
        sessions.forEach((key, session) -> {
            if (key.startsWith(deviceCode + ":") && session.isOpen()) {
                try { session.getBasicRemote().sendText(message); }
                catch (IOException e) { log.warn("[WS] 推送失败 key={}", key); }
            }
        });
    }
}
