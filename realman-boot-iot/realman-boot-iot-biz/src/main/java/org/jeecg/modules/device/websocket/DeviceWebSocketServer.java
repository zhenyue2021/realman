package org.jeecg.modules.device.websocket;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.jeecg.modules.device.constant.DeviceConstant;

import java.io.EOFException;
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
 *   <li>{@code deviceCode} 为 {@code realman}：接收所有设备的上下线事件和状态推送（全局监控页使用）</li>
 * </ul>
 *
 * <p>集群推送模型：
 * <pre>
 *   MQTT 消息 → Node B 处理 → pushXxx() → Redis Pub/Sub 广播
 *                                            ↓
 *                               所有节点收到 onMessage()
 *                                    ↓
 *                              send() 推送本节点的本地 WebSocket sessions
 * </pre>
 * 无论浏览器连接到哪个节点，该节点的 {@link #onMessage} 都会触发并推送，保证消息必达。
 *
 * <p>注意：{@link ServerEndpoint} 的 WebSocket 框架会为每个连接创建新实例，
 * 因此 {@link #sessions} 必须是 {@code static} 共享的；
 * {@link #redisTemplate} 通过 {@link Autowired} 注入到 Spring 管理的单例实例上，
 * {@code pushXxx()} 方法均在该单例上调用，injection 正确可用。
 */
@Slf4j
@Component
@ServerEndpoint("/ws/device/{deviceCode}")
public class DeviceWebSocketServer implements MessageListener {

    /** Redis 广播频道前缀：iot:ws:push:{targetCode}（targetCode 可为 deviceCode 或 {@code realman}） */
    public static final String WS_PUSH_CHANNEL_PREFIX = "iot:ws:push:";
    /** 全局监控页编码 */
    public static final String REALMAN_CODE = "realman";

    /** 活跃 WebSocket 会话映射，Key = {deviceCode}:{sessionId}，支持并发访问 */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * field 注入（@ServerEndpoint 与构造器注入存在兼容性问题，统一使用 @Autowired field 注入）。
     * 仅在 Spring 管理的单例实例上有效，WebSocket 框架创建的连接实例无此注入，
     * 但连接实例只使用 static sessions，不需要 redisTemplate。
     */
    @Autowired
    private StringRedisTemplate redisTemplate;

    // -------------------------------------------------------------------------
    // WebSocket 生命周期回调（由 WebSocket 框架在连接实例上调用，只操作 static sessions）
    // -------------------------------------------------------------------------

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
        if (e instanceof EOFException) {
            log.info("[WS] 客户端中断连接 sessionId={}", session.getId());
            return;
        }
        log.error("[WS] 错误 sessionId={}", session != null ? session.getId() : "null", e);
    }

    // -------------------------------------------------------------------------
    // 公开推送方法（由 MQTT Handler 调用，均在 Spring 单例实例上执行）
    // 改为通过 Redis Pub/Sub 广播，保证集群内所有节点均可推送到各自的本地 sessions
    // -------------------------------------------------------------------------

    /**
     * 推送 设备状态 数据（由 DeviceStatusHandler 在收到 {deviceCode}/status 后调用）
     * @param deviceCode    设备代码
     * @param statusJson    设备状态实时数据JSON格式
     */
    public void pushDeviceStatus(String deviceCode, String statusJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.STATUS, deviceCode, statusJson);
        redisPublish(deviceCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }



    /**
     * 推送"机器人原始状态"数据（由 RobotSlaveStatusHandler 在收到 {robotCode}/slave/status 后调用）
     *
     * <p>与 pushDeviceStatus 区分开来，type 使用 ROBOT_STATUS，便于前端区分展示逻辑。
     *
     * @param robotCode  机器人设备编码
     * @param statusJson 机器人状态 JSON（原始上报数据）
     */
    public void pushRobotStatus(String robotCode, String statusJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.ROBOT_STATUS, robotCode, statusJson);
        redisPublish(robotCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    /**
     * 推送"主控设备原始状态"数据（由 RobotSlaveStatusHandler.handleMasterStatus 在收到 {controllerCode}/master/states 后调用）
     *
     *
     * @param robotCode  机器人设备编码
     * @param statusJson 机器人状态 JSON（原始上报数据）
     */
    public void pushMasterStatus(String robotCode, String statusJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.MASTER_STATUS, robotCode, statusJson);
        redisPublish(robotCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    /**
     * 推送"主控设备指令"数据（由 MasterCommandHandler#handle 在收到 {controllerCode}/master/cmd 后调用）
     *
     *
     * @param controllerCode  主控设备编码
     * @param cmdJson 主控设备上报指令 JSON（原始上报数据）
     */
    public void pushMasterCmdStatus(String controllerCode, String cmdJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.MASTER_CMD, controllerCode, cmdJson);
        redisPublish(controllerCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    /**
     * 推送设备上线/下线事件（由 DeviceOnlineOfflineHandler 在处理 $SYS 事件后调用）
     *
     * <p>同时推送给该设备的订阅者和 REALMAN_CODE 全局订阅者。
     *
     * @param deviceCode 设备编号
     * @param online     true=上线，false=下线
     */
    public void pushDeviceOnlineStatus(String deviceCode, boolean online) {
        String msg = "{\"type\":\"" + DeviceConstant.WebSocketType.ONLINE_STATUS + "\",\"deviceCode\":\"" + deviceCode
                + "\",\"online\":" + online + ",\"ts\":" + System.currentTimeMillis() + "}";
        redisPublish(deviceCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    /**
     * 推送 OTA 升级进度（由 OtaProgressHandler 在收到设备 OTA 进度上报后调用）
     *
     *
     * @param deviceCode 设备编号
     * @param taskId     升级任务 ID
     * @param status     当前升级状态码（参考 DeviceConstant.OtaUpgradeStatus）
     * @param progress   下载进度（0-100 百分比），可为 null
     */
    public void pushOtaProgress(String deviceCode, String taskId, Integer status, Integer progress) {
        String msg = "{\"type\":\"" + DeviceConstant.WebSocketType.OTA_PROGRESS + "\",\"deviceCode\":\"" + deviceCode
                + "\",\"taskId\":\"" + taskId + "\",\"status\":" + status
                + ",\"progress\":" + progress + "}";
        redisPublish(deviceCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    /**
     * 推送工单开始提醒（到达 plan_start_time）
     *
     * <p>只推送给该主控设备（device_type=2）的订阅者。
     *
     * @param controllerCode 主控 deviceCode
     * @param workOrderJson  工单 JSON（建议为 WorkOrder 对象序列化结果）
     */
    public void pushPendingWorkOrder(String controllerCode, String workOrderJson) {
        redisPublish(controllerCode, buildMsg(DeviceConstant.WebSocketType.WORK_ORDER_PENDING, controllerCode, workOrderJson));
    }

    /**
     * 推送工单已开始提醒
     *
     * <p>只推送给该主控设备（device_type=2）的订阅者。
     *
     * @param controllerCode 主控 deviceCode
     * @param workOrderJson  工单 JSON（建议为 WorkOrder 对象序列化结果）
     */
    public void pushStartedWorkOrder(String controllerCode, String workOrderJson) {
        redisPublish(controllerCode, buildMsg(DeviceConstant.WebSocketType.WORK_ORDER_STARTED, controllerCode, workOrderJson));
    }

    /**
     * 推送主控"当前关联设备信息"（登录后触发查询）
     *
     * <p>只推送给该主控设备（device_type=2）的订阅者。
     *
     * @param controllerCode 主控 deviceCode
     * @param dataJson       JSON 对象字符串（例如：{code,message,robot:{...}}）
     */
    public void pushAssociatedDeviceInfo(String controllerCode, String dataJson) {
        redisPublish(controllerCode, buildMsg(DeviceConstant.WebSocketType.ASSOCIATED_DEVICE_INFO, controllerCode, dataJson));
    }

    // -------------------------------------------------------------------------
    // Redis Pub/Sub（MessageListener）：收到广播后推送到本节点的本地 sessions
    // -------------------------------------------------------------------------

    /**
     * Redis 消息到达：从 channel 解析 targetCode，推送到本节点匹配的本地 sessions。
     * 集群内所有节点均会收到此回调，各自推送自己持有的 sessions，保证消息全覆盖。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String targetCode = channel.substring(WS_PUSH_CHANNEL_PREFIX.length());
        String msg = new String(message.getBody());
        send(targetCode, msg);
    }

    // -------------------------------------------------------------------------
    // 内部方法
    // -------------------------------------------------------------------------

    /**
     * 构建标准推送 JSON：{"type":"<type>","deviceCode":"<code>","data":<jsonStr>}
     *
     * @param type    消息类型，如 STATUS / ROBOT_STATUS / MASTER_CMD 等
     * @param code    设备编码
     * @param jsonStr data 字段值（已序列化的 JSON 字符串，直接内嵌，不做二次转义）
     */
    private static String buildMsg(String type, String code, String jsonStr) {
        return "{\"type\":\"" + type + "\",\"deviceCode\":\"" + code + "\",\"data\":" + jsonStr + "}";
    }

    /**
     * 向 Redis 频道发布消息；若 Redis 不可用则降级为本节点本地推送。
     *
     * @param targetCode deviceCode 或 REALMAN_CODE
     * @param message    完整推送 JSON
     */
    private void redisPublish(String targetCode, String message) {
        try {
            redisTemplate.convertAndSend(WS_PUSH_CHANNEL_PREFIX + targetCode, message);
        } catch (Exception e) {
            log.warn("[WS] Redis 发布失败，降级本地推送: targetCode={}, err={}", targetCode, e.getMessage());
            send(targetCode, message);
        }
    }

    /**
     * 向指定 targetCode 的所有本地活跃 sessions 推送消息。
     * Key 前缀匹配：{targetCode}: 或 all:
     */
    private void send(String targetCode, String message) {
        sessions.forEach((key, session) -> {
            if (key.startsWith(targetCode + ":") && session.isOpen()) {
                try {
                    String preview = message != null && message.length() > 500 ? message.substring(0, 500) + "..." : message;
                    log.debug("[WS] send key={}, preview={}", key, preview);
                    session.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    log.warn("[WS] 推送失败 key={}", key);
                }
            }
        });
    }
}
