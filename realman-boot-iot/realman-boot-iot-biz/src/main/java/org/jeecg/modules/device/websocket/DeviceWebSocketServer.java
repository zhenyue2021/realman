package org.jeecg.modules.device.websocket;

import jakarta.annotation.PostConstruct;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.jeecg.modules.device.constant.DeviceConstant;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 * <p>长连接由 Spring {@link org.springframework.web.socket.WebSocketHandler}
 *（{@link org.jeecg.modules.device.config.DeviceWebSocketMvcConfig}）注册；{@link #sessions} 仍为 {@code static}。
 * {@link #redisTemplate} 注入在 Spring 单例上，{@code pushXxx()} 均在单例实例上调用。
 */
@Slf4j
@Component
public class DeviceWebSocketServer implements MessageListener {

    /** Redis 广播频道前缀：iot:ws:push:{targetCode}（targetCode 可为 deviceCode 或 {@code realman}） */
    public static final String WS_PUSH_CHANNEL_PREFIX = "iot:ws:push:";
    /** 全局监控页编码 */
    public static final String REALMAN_CODE = "realman";

    /** 活跃 WebSocket 会话映射，Key = {deviceCode}:{sessionId}，支持并发访问 */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 高频状态消息节流缓存，Key = targetCode，Value = 待推送的最新消息。
     * 设备每 10ms 上报一次时，中间帧对前端无意义，只推最新一帧即可。
     * 定时任务每 100ms 将缓存中的最新消息推出并清空，避免消息积压与前端收到过时数据。
     *
     * <p>集群安全：flush 时调用 {@link #redisPublish} 而非 {@code send}，消息经 Redis Pub/Sub
     * 广播到所有节点，各节点的 {@link #onMessage} 再推送本地 sessions，保证跨节点推达。
     */
    private static final Map<String, String> latestMessages = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService throttleScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-throttle");
                t.setDaemon(true);
                return t;
            });

    /**
     * Spring 管理的单例实例引用，供节流调度器线程调用实例方法 {@link #redisPublish}。
     * volatile 保证多线程可见性。
     */
    private static volatile DeviceWebSocketServer instance;

    /** field 注入：推送与 Redis Pub/Sub 均在本 Spring 单例上执行 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * Spring 初始化完成后启动节流调度器。
     *
     * <p>使用 {@link PostConstruct} 而非 static 块，确保调度器 flush 时能通过 {@link #instance}
     * 访问已完成依赖注入的单例，调用 {@link #redisPublish} 走 Redis Pub/Sub 广播路径，
     * 实现集群内所有节点推送到各自的本地 WebSocket sessions。
     */
    @PostConstruct
    public void startThrottleScheduler() {
        DeviceWebSocketServer.instance = this;
        // 每 100ms 将各 targetCode 的最新消息发布到 Redis，推完移除，保证前端看到的始终是最新帧
        throttleScheduler.scheduleAtFixedRate(() -> {
            latestMessages.forEach((targetCode, msg) -> {
                latestMessages.remove(targetCode);
                instance.redisPublish(targetCode, msg);
            });
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    // -------------------------------------------------------------------------
    // Spring WebSocket 建立/释放（由 DeviceWebSocketChannelHandler 调用，只操作 static sessions）
    // -------------------------------------------------------------------------

    /** 供 {@link DeviceWebSocketChannelHandler} 在握手成功后登记本节点会话 */
    public static void registerManagedSession(String deviceCode, Session session) {
        sessions.put(deviceCode + ":" + session.getId(), session);
        log.info("[WS] 客户端连接 deviceCode={}, sessionId={}", deviceCode, session.getId());
    }

    public static void removeManagedSession(String deviceCode, Session session) {
        sessions.remove(deviceCode + ":" + session.getId());
        log.debug("[WS] 客户端断开 deviceCode={}, sessionId={}", deviceCode, session.getId());
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
        throttle(robotCode, msg);
        throttle(REALMAN_CODE, msg);
    }

    /**
     * 推送"主控设备原始状态"数据（由 RobotSlaveStatusHandler.handleMasterStatus 在收到 {controllerCode}/master/states 后调用）
     *
     * @param robotCode  主控设备编码
     * @param statusJson 主控状态 JSON（原始上报数据）
     */
    public void pushMasterStatus(String robotCode, String statusJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.MASTER_STATUS, robotCode, statusJson);
        throttle(robotCode, msg);
        throttle(REALMAN_CODE, msg);
    }

    /**
     * 推送"主控设备指令"数据（由 MasterCommandHandler#handle 在收到 {controllerCode}/master/cmd 后调用）
     *
     * @param controllerCode  主控设备编码
     * @param cmdJson 主控设备上报指令 JSON（原始上报数据）
     */
    public void pushMasterCmdStatus(String controllerCode, String cmdJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.MASTER_CMD, controllerCode, cmdJson);
        throttle(controllerCode, msg);
        throttle(REALMAN_CODE, msg);
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

    /**
     * 推送 WebRTC 信令服务重启事件（由 WebRtcRestartHandler 在收到 device/{deviceCode}/webrtc/restart 后调用）
     *
     * @param deviceCode   设备编码
     * @param restartJson  解密后的原始上报 JSON（直接作为 data 字段内嵌）
     */
    public void pushWebRtcRestart(String deviceCode, String restartJson) {
        String msg = buildMsg(DeviceConstant.WebSocketType.WEBRTC_RESTART, deviceCode, restartJson);
        redisPublish(deviceCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    public void pushSlamStates(String deviceCode, String jsonStr) {
        String msg = buildMsg("SLAM_STATUS", deviceCode, jsonStr);
        redisPublish(deviceCode, msg);
        redisPublish(REALMAN_CODE, msg);
    }

    /**
     * 推送 SLAM 指令终态结果给主控前端。
     *
     * <p>消息 type 直接使用功能名称（如 SwitchMode / GetCurrentMap / SaveMap 等），
     * 便于前端按 function 分别处理响应逻辑，无需再解析 data 内部字段来判断类型。
     *
     * @param masterCode   主控 deviceCode
     * @param functionName SLAM 功能名称（即消息 type）
     * @param ackJson      终态记录 JSON
     */
    public void pushSlamAck(String masterCode, String functionName, String ackJson) {
        String msg = buildMsg(functionName, masterCode, ackJson);
        redisPublish(masterCode, msg);
        redisPublish(REALMAN_CODE, msg);
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
     * 高频消息节流：只保留每个 targetCode 的最新消息，由定时任务统一推送。
     * 适用于设备状态类高频上报（10ms/次），避免前端积压旧帧。
     */
    private void throttle(String targetCode, String message) {
        latestMessages.put(targetCode, message);
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
     *
     * <p><b>并发安全：</b>JSR-356 规定 {@link RemoteEndpoint.Basic} 不允许多线程并发写同一会话。
     * MQTT 回调线程与 Redis onMessage 线程可能同时向同一连接推送，导致
     * {@code TEXT_FULL_WRITING} 异常。对每个 session 加 {@code synchronized} 保证串行写入。
     */
    private static void send(String targetCode, String message) {
        sessions.forEach((key, session) -> {
            if (key.startsWith(targetCode + ":") && session.isOpen()) {
                synchronized (session) {
                    if (!session.isOpen()) return;
                    try {
                        String preview = message != null && message.length() > 500 ? message.substring(0, 500) + "..." : message;
                        log.debug("[WS] send key={}, preview={}", key, preview);
                        session.getBasicRemote().sendText(message);
                    } catch (IOException | IllegalStateException e) {
                        log.warn("[WS] 推送失败 key={}", key, e);
                    }
                }
            }
        });
    }

}
