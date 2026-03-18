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
 *
 * <p>端点地址：{@code ws://host/realman-iot/ws/device/{deviceCode}}
 *
 * <p>连接规则：
 * <ul>
 *   <li>{@code deviceCode} 为具体设备编号：只接收该设备的状态/事件推送</li>
 *   <li>{@code deviceCode} 为 {@code all}：接收所有设备的上下线事件和状态推送（全局监控页使用）</li>
 * </ul>
 *
 * <p>推送事件类型：
 * <ul>
 *   <li>STATUS：设备状态上报（温湿度/电量/信号等实时数据）</li>
 *   <li>ONLINE_STATUS：设备上线/下线事件</li>
 *   <li>OTA_PROGRESS：OTA 升级进度（下载百分比/升级状态）</li>
 * </ul>
 *
 * <p>会话管理：使用 {@link ConcurrentHashMap} 存储活跃连接，Key = {deviceCode}:{sessionId}，
 * 支持同一 deviceCode 多个并发连接（多浏览器/多标签）。
 *
 * <p>注意：{@link ServerEndpoint} 注解的类每次连接都会实例化，因此 sessions 必须是静态共享的。
 */
@Slf4j
@Component
@ServerEndpoint("/ws/device/{deviceCode}")
public class DeviceWebSocketServer {

    /** 活跃 WebSocket 会话映射，Key = {deviceCode}:{sessionId}，支持并发访问 */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 客户端建立 WebSocket 连接时触发
     *
     * @param session    WebSocket 会话对象
     * @param deviceCode URL 路径参数（订阅的设备编号或 "all"）
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("deviceCode") String deviceCode) {
        sessions.put(deviceCode + ":" + session.getId(), session);
        log.info("[WS] 客户端连接 deviceCode={}, sessionId={}", deviceCode, session.getId());
    }

    /**
     * 客户端断开 WebSocket 连接时触发（正常关闭或网络异常均会触发）
     *
     * @param session    WebSocket 会话对象
     * @param deviceCode URL 路径参数
     */
    @OnClose
    public void onClose(Session session, @PathParam("deviceCode") String deviceCode) {
        sessions.remove(deviceCode + ":" + session.getId());
        log.debug("[WS] 客户端断开 deviceCode={}, sessionId={}", deviceCode, session.getId());
    }

    /**
     * WebSocket 通信发生错误时触发
     *
     * @param session WebSocket 会话对象
     * @param e       异常信息
     */
    @OnError
    public void onError(Session session, Throwable e) {
        log.error("[WS] 错误 sessionId={}", session.getId(), e);
    }

    /**
     * 推送设备实时状态数据（由 DeviceStatusHandler 在收到设备上报后调用）
     *
     * <p>同时推送给该设备的订阅者和 "all" 全局订阅者。
     *
     * @param deviceCode 设备编号
     * @param statusJson 设备状态 JSON 字符串（解密后的原始上报数据）
     */
    public void pushDeviceStatus(String deviceCode, String statusJson) {
        String msg = "{\"type\":\"STATUS\",\"deviceCode\":\"" + deviceCode + "\",\"data\":" + statusJson + "}";
        send(deviceCode, msg);
        send("all", msg);
    }

    /**
     * 推送“机器人原始状态”数据（由 RobotSlaveStatusHandler 在收到 {robotCode}/slave/status 后调用）
     *
     * <p>与 pushDeviceStatus 区分开来，type 使用 ROBOT_STATUS，便于前端区分展示逻辑。
     *
     * @param robotCode  机器人设备编码
     * @param statusJson 机器人状态 JSON（原始上报数据）
     */
    public void pushRobotStatus(String robotCode, String statusJson) {
        String msg = "{\"type\":\"ROBOT_STATUS\",\"deviceCode\":\"" + robotCode + "\",\"data\":" + statusJson + "}";
        send(robotCode, msg);
        send("all", msg);
    }

    /**
     * 推送设备上线/下线事件（由 DeviceOnlineOfflineHandler 在处理 $SYS 事件后调用）
     *
     * <p>同时推送给该设备的订阅者和 "all" 全局订阅者。
     *
     * @param deviceCode 设备编号
     * @param online     true=上线，false=下线
     */
    public void pushDeviceOnlineStatus(String deviceCode, boolean online) {
        String msg = "{\"type\":\"ONLINE_STATUS\",\"deviceCode\":\"" + deviceCode
                + "\",\"online\":" + online + ",\"ts\":" + System.currentTimeMillis() + "}";
        send(deviceCode, msg);
        send("all", msg);
    }

    /**
     * 推送 OTA 升级进度（由 OtaProgressHandler 在收到设备 OTA 进度上报后调用）
     *
     * <p>只推送给该设备的订阅者（OTA 进度为设备级事件，不广播到 "all"）。
     *
     * @param deviceCode 设备编号
     * @param taskId     升级任务 ID
     * @param status     当前升级状态码（参考 DeviceConstant.OtaUpgradeStatus）
     * @param progress   下载进度（0-100 百分比），可为 null
     */
    public void pushOtaProgress(String deviceCode, String taskId, Integer status, Integer progress) {
        String msg = "{\"type\":\"OTA_PROGRESS\",\"deviceCode\":\"" + deviceCode
                + "\",\"taskId\":\"" + taskId + "\",\"status\":" + status
                + ",\"progress\":" + progress + "}";
        send(deviceCode, msg);
    }

    /**
     * 推送工单开始提醒（到达 plan_start_time）
     *
     * <p>只推送给该主控设备（device_type=2）的订阅者。
     *
     * @param controllerCode 主控 deviceCode
     * @param workOrderJson  工单 JSON（建议为 WorkOrder 对象序列化结果）
     */
    public void pushWorkOrderStart(String controllerCode, String workOrderJson) {
        String msg = "{\"type\":\"WORK_ORDER_START\",\"deviceCode\":\"" + controllerCode + "\",\"data\":" + workOrderJson + "}";
        send(controllerCode, msg);
    }

    /**
     * 推送主控“当前关联设备信息”（登录后触发查询）
     *
     * <p>只推送给该主控设备（device_type=2）的订阅者。
     *
     * @param controllerCode 主控 deviceCode
     * @param dataJson       JSON 对象字符串（例如：{code,message,robot:{...}}）
     */
    public void pushAssociatedDeviceInfo(String controllerCode, String dataJson) {
        String msg = "{\"type\":\"ASSOCIATED_DEVICE_INFO\",\"deviceCode\":\"" + controllerCode + "\",\"data\":" + dataJson + "}";
        send(controllerCode, msg);
    }

    /**
     * 向指定 deviceCode 的所有活跃会话推送消息
     *
     * <p>遍历 sessions，Key 以 "{deviceCode}:" 开头且会话处于打开状态的连接均会收到消息。
     * 若推送失败（如网络中断），仅记录警告日志，不影响其他会话。
     *
     * @param deviceCode 目标 deviceCode（或 "all" 表示全局订阅者）
     * @param message    推送内容（JSON 字符串）
     */
    private void send(String deviceCode, String message) {
        sessions.forEach((key, session) -> {
            if (key.startsWith(deviceCode + ":") && session.isOpen()) {
                try {
                    // 在 send() 方法里，session.isOpen() 通过后
                    String preview = message != null && message.length() > 500 ? message.substring(0, 500) + "..." : message;
                    log.info("[WS] send key={}, preview={}", key, preview);
                    session.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    log.warn("[WS] 推送失败 key={}", key);
                }
            }
        });
    }
}
