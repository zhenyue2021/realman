package org.jeecg.modules.device.mqtt.handler;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.jeecg.common.trace.TraceIdConst;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.handler.CollectUrlRequestHandler;
import org.jeecg.modules.device.datacollect.handler.OssAddressReportHandler;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT 消息分发器
 *
 * <p>职责：接收 {@link org.jeecg.modules.device.config.MqttConfig} 订阅的全部消息，
 * 按 Topic 路径路由到对应的业务 Handler。
 *
 * <p>路由规则：
 * <pre>
 *   $SYS/.../clients/.../connected       → DeviceOnlineOfflineHandler.handleOnline()
 *   $SYS/.../clients/.../disconnected    → DeviceOnlineOfflineHandler.handleOffline()
 *
 *   device/{code}/status/report          → DeviceStatusHandler.handle()
 *   device/{code}/config/ack             → DeviceConfigAckHandler.handle()
 *   device/{code}/command/{cmd}/ack      → DeviceCommandAckHandler.handle()
 *   device/{code}/ota/progress           → OtaProgressHandler.handle()
 *   device/{code}/log/operation          → DeviceOperationLogHandler.handle()
 *   device/{code}/camera/stream/ack → DeviceCameraStreamResponseHandler.handle()
 *   master/{code}/teleop/associated-device/ack → MasterAssociatedDeviceResponseHandler.handle()
 *   device/{code}/teleop/associated-device/ack → MasterAssociatedDeviceResponseHandler.handle()（同上业务，备用 Topic）
 *   device/{code}/datacollect/deviceOnline    → DeviceOnlineReportHandler.handle()（设备主动上线上报，更新 DB 并推 MQ）
 *
 *   {code}/master/{action}               → 主控设备原始上报（cmd/states/rtsp/ctrl 等）
 *   {code}/slave/{action}                → 机器人设备原始上报（cmd/states 等）
 * </pre>
 *
 * <p>注意：设备身份鉴权已在 EMQX 连接层完成（HTTP Auth 回调），
 * 此处无需再做身份验证，直接按 Topic 路由处理业务逻辑。
 *
 * @see org.jeecg.modules.device.config.MqttConfig MQTT 客户端连接与 Topic 订阅
 * @see org.jeecg.modules.device.config.MqttConfigContext 解决循环依赖，供 MqttConfig 获取本类实例
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MqttMessageDispatcher {

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    /** 匹配 device/{deviceCode}/{path}，group(1)=deviceCode，group(2)=path */
    private static final Pattern DEVICE_TOPIC     = Pattern.compile("^device/([^/]+)/(.+)$");
    /** keepalive：device/{deviceCode}/status/report */
    private static final Pattern STATUS_REPORT_TOPIC = Pattern.compile("^device/([^/]+)/status/report$");
    /** 平台自检心跳 Topic（必须与 MqttClientWatchdog.HEARTBEAT_TOPIC 和 MqttConfig 订阅列表保持一致） */
    private static final String HEARTBEAT_TOPIC = "iot-platform/heartbeat";
    /** 匹配 {deviceCode}/master/{path} 或 {deviceCode}/slave/{path}，group(1)=deviceCode，group(2)=role，group(3)=path */
    private static final Pattern RAW_DEVICE_TOPIC = Pattern.compile("^([^/]+)/(master|slave)/(.+)$");

    private final DeviceStatusHandler                 statusHandler;
    private final DeviceConfigAckHandler              configAckHandler;
    private final DeviceCommandAckHandler             commandAckHandler;
    private final MasterCommandAckHandler             masterCommandAckHandler;
    private final OtaProgressHandler                  otaProgressHandler;
    private final DeviceOperationLogHandler           operationLogHandler;
    private final DeviceOnlineOfflineHandler          onlineOfflineHandler;
    private final DeviceCameraStreamResponseHandler   deviceCameraStreamResponseHandler;
    private final MasterAssociatedDeviceResponseHandler masterAssociatedDeviceResponseHandler;
    private final RobotSlaveStatusHandler             robotSlaveStatusHandler;
    private final SlamAckHandler                      slamAckHandler;
    private final SlamStatesHandler                   slamStatesHandler;
    private final ExtParamsRequestHandler             extParamsRequestHandler;
    private final MasterCommandHandler                masterCommandHandler;
    private final WebRtcAckHandler                    webRtcAckHandler;
    private final WebRtcRestartHandler                webRtcRestartHandler;
    private final OssAddressReportHandler             ossAddressReportHandler;
    /** darwin.integration.enabled=false 时 Bean 不存在，注入 null，dispatch 时做空判断 */
    @Autowired(required = false)
    private CollectUrlRequestHandler                  collectUrlRequestHandler;
    private final DeviceOnlineReportHandler           deviceOnlineReportHandler;

    /** IoT 设备任务线程池，由 AppConfig 定义，已内置 MDC 跨线程传播。
     *  非 final：@Qualifier 不随 Lombok @RequiredArgsConstructor 传播到构造器参数，
     *  改用 @Autowired setter 注入以确保 Spring 选到正确的 Bean。 */
    @Autowired
    @Qualifier("deviceTaskExecutor")
    private Executor taskExecutor;

    /**
     * 高频上报 Topic 的节流间隔（毫秒）。
     * 500ms = 2次/秒，1000ms = 1次/秒。
     * 对 slam/states、slave/states、master/states 生效；其他 Topic 不受影响。
     */
    @Value("${mqtt.high-freq-throttle-ms:500}")
    private long highFreqThrottleMs;

    /**
     * 节流时间戳表：key=topic（含设备编码，每台设备独立节流），value=上次投递时间戳(ms)。
     * 最多 N 台设备×高频 Topic 数条目，内存可忽略；设备掉线后条目保留但无害。
     */
    private final ConcurrentHashMap<String, Long> throttleTimestamps = new ConcurrentHashMap<>();

    /** 需要节流的高频 Topic 后缀集合 */
    private static final List<String> HIGH_FREQ_SUFFIXES =
            List.of("/slam/states", "/slave/states", "/master/states");

    /** 最后一次收到 MQTT 消息的时间戳（毫秒）；由 Watchdog 读取，用于检测 Paho 僵死状态 */
    public static final AtomicLong lastReceivedTs = new AtomicLong(System.currentTimeMillis());

    /**
     * keepalive 专用单线程池：仅用于 status/report 的 Redis TTL 刷新。
     * 不借用 deviceTaskExecutor，保证即使业务队列满载也不影响续期；
     * 单线程足够（keepalive 写入轻量，无并发瓶颈），不阻塞 Paho CommsCallback 线程。
     * Spring 容器销毁时由 {@link #shutdownKeepaliveExecutor()} 优雅关闭。
     */
    private final ExecutorService keepaliveExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "device-keepalive"));

    @PreDestroy
    void shutdownKeepaliveExecutor() {
        keepaliveExecutor.shutdown();
        try {
            if (!keepaliveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                keepaliveExecutor.shutdownNow();
                if (!keepaliveExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] keepaliveExecutor 未在时限内结束，可能仍有任务未完成");
                }
            }
        } catch (InterruptedException e) {
            keepaliveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[Dispatcher] keepaliveExecutor 已关闭");
    }

    /**
     * 异步分发入口：Paho messageArrived 回调调用此方法，立即将消息投递到线程池后返回，
     * 保证 Paho 唯一接收线程不被任何 Handler 的 I/O 操作阻塞。
     *
     * <p>高频上报 Topic（slam/states 等）在投递前先做节流检查，超出阈值的帧直接丢弃，
     * 不进入线程池队列，彻底避免 EMQX mqueue 打满。
     *
     * <p>{@code status/report} keepalive 在入队前提交到 {@code keepaliveExecutor}（专用单线程池）
     * 异步刷新 Redis TTL，既不阻塞 Paho CommsCallback 线程，也不受 {@code deviceTaskExecutor}
     * 队列满丢弃影响，保证离线判定不误判。
     */
    public void dispatch(String topic, MqttMessage message) {
        String topicNorm = (topic != null && topic.startsWith("/")) ? topic.substring(1) : topic;

        // 节流：高频上报 Topic 每 highFreqThrottleMs 只处理一次，直接在 Paho 线程判断后丢弃
        if (isThrottled(topicNorm)) {
            return;
        }

        // 更新存活时间戳，供 MqttClientWatchdog 检测 Paho 僵死状态
        lastReceivedTs.set(System.currentTimeMillis());

        // 心跳自检 topic：lastReceivedTs 已更新，无需业务处理，直接返回
        if (HEARTBEAT_TOPIC.equals(topicNorm)) {
            return;
        }

        // 在 Paho 接收线程立即拷贝数据，避免 Paho 内部回收 byte[]
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        String traceId = extractTraceId(message);

        Matcher keepalive = STATUS_REPORT_TOPIC.matcher(topicNorm);
        if (keepalive.matches()) {
            String keepaliveDeviceCode = keepalive.group(1);
            // 提交到专用单线程池，Paho CommsCallback 线程立即返回，不因 Redis I/O 阻塞
            keepaliveExecutor.execute(() ->
                    statusHandler.refreshKeepalivePresence(keepaliveDeviceCode, payload));
        }

        taskExecutor.execute(() -> doDispatch(topicNorm, payload, traceId));
    }

    /**
     * 节流检查：仅对高频 Topic 生效。
     * 同一 topic 上次投递距今不足 highFreqThrottleMs 则返回 true（丢弃）。
     * 使用 topic 全路径作为 key，每台设备独立计时。
     */
    private boolean isThrottled(String topic) {
        if (topic == null) return false;
        boolean isHighFreq = false;
        for (String suffix : HIGH_FREQ_SUFFIXES) {
            if (topic.endsWith(suffix)) {
                isHighFreq = true;
                break;
            }
        }
        if (!isHighFreq) return false;

        long now = System.currentTimeMillis();
        Long last = throttleTimestamps.get(topic);
        if (last != null && now - last < highFreqThrottleMs) {
            return true;
        }
        throttleTimestamps.put(topic, now);
        return false;
    }

    private void doDispatch(String topic, String payload, String traceId) {

        // 链路追踪：traceId 由 dispatch() 在 Paho 线程提取并传入，此处直接使用
        if (!StringUtils.hasText(traceId)) {
            traceId = "mqtt-" + IdUtil.fastSimpleUUID();
        }
        MDC.put(TraceIdConst.MDC_TRACE_ID, traceId);
        MDC.put(TraceIdConst.MDC_SPAN_ID,  generateSpanId());
        MDC.put(TraceIdConst.MDC_SERVICE,   serviceName);
        MDC.put(TraceIdConst.MDC_SOURCE,    "mqtt");
        MDC.put("mqttTopic", topic);

        try {
            // 0. $SYS 系统事件（设备上下线）
            if (topic.contains("/clients/") && topic.contains("/connected")) {
                onlineOfflineHandler.handleOnline(topic, payload);
                return;
            }
            if (topic.contains("/clients/") && topic.contains("/disconnected")) {
                onlineOfflineHandler.handleOffline(topic, payload);
                return;
            }

            // 1. 标准业务 Topic：device/{deviceCode}/{path}
            Matcher m = DEVICE_TOPIC.matcher(topic);
            if (m.matches()) {
                dispatchDeviceTopic(m.group(1), m.group(2), topic, payload);
                return;
            }
            // 2. 主控指令 ACK：master/{controllerCode}/command/{cmd}/ack
            if (topic.startsWith("master/") && topic.contains("/command/") && topic.endsWith("/ack")) {
                String[] parts = topic.split("/");
                if (parts.length == 5) {
                    String controllerCode = parts[1];
                    String cmd = parts[3];
                    masterCommandAckHandler.handle(controllerCode, cmd, payload);
                    return;
                }
            }
            // 2b. 主控关联设备响应（与 {@link org.jeecg.modules.device.config.MqttConfig} 订阅 master/+/teleop/associated-device/ack 一致）
            if (topic.startsWith("master/") && topic.endsWith("/teleop/associated-device/ack")) {
                String[] parts = topic.split("/");
                if (parts.length == 5) {
                    masterAssociatedDeviceResponseHandler.handle(parts[1], payload);
                    return;
                }
            }

            // 3. 主控/机器人原始上报 Topic：{deviceCode}/master/{path} 或 {deviceCode}/slave/{path}
            Matcher raw = RAW_DEVICE_TOPIC.matcher(topic);
            if (raw.matches()) {
                dispatchRawTopic(raw.group(1), raw.group(2), raw.group(3), payload);
                return;
            }

            log.warn("[Dispatcher] 未识别Topic: {}", topic);
        } catch (Exception e) {
            log.error("[Dispatcher] 处理消息异常 topic={}", topic, e);
        } finally {
            MDC.clear();
        }
    }

    private String extractTraceId(MqttMessage message) {
        MqttProperties props = message.getProperties();
        if (props == null) return null;
        List<UserProperty> userProperties = props.getUserProperties();
        if (userProperties == null) return null;
        for (UserProperty prop : userProperties) {
            if (TraceIdConst.HEADER_TRACE_ID.equals(prop.getKey())) {
                return prop.getValue();
            }
        }
        return null;
    }

    /**
     * 路由 device/{deviceCode}/{path} 格式的标准业务 Topic
     */
    private void dispatchDeviceTopic(String deviceCode, String path, String topic, String payload) throws Exception {
        // 指令集通用 ACK：command/{cmd}/ack
        if (path.startsWith("command/") && path.endsWith("/ack")) {
            String cmd = path.substring("command/".length(), path.length() - "/ack".length());
            if (cmd.contains("/")) {
                log.warn("[Dispatcher] 未知指令ACK路径: {}", topic);
                return;
            }
            commandAckHandler.handle(deviceCode, cmd, payload);
            return;
        }

        switch (path) {
            case "status/report"                      -> statusHandler.handle(deviceCode, payload);
            case "config/ack"                         -> configAckHandler.handle(deviceCode, payload);
            case "ota/progress"                       -> otaProgressHandler.handle(deviceCode, payload);
            case "log/operation"                      -> operationLogHandler.handle(deviceCode, payload);
            case "camera/stream/ack"                  -> deviceCameraStreamResponseHandler.handle(deviceCode, payload);
            case "teleop/associated-device/ack"       -> masterAssociatedDeviceResponseHandler.handle(deviceCode, payload);
            case "slam/ack"                           -> slamAckHandler.handle(deviceCode, payload);
            case "slam/states"                        -> slamStatesHandler.handle(deviceCode, payload);
            case "ext-params/request"                 -> extParamsRequestHandler.handle(deviceCode, payload);
            case "webrtc/ack"                         -> webRtcAckHandler.handle(deviceCode, payload);
            case "webrtc/restart"                     -> webRtcRestartHandler.handle(deviceCode, payload);
            case DataCollectConstant.MQTT_UP_COLLECT_URL_REQUEST -> {
                if (collectUrlRequestHandler != null) collectUrlRequestHandler.handle(deviceCode, payload);
                else log.warn("[Dispatcher] Darwin 集成未启用，忽略 collectUrlRequest deviceCode={}", deviceCode);
            }
            case DataCollectConstant.MQTT_UP_OSS_ADDRESS_REPORT -> ossAddressReportHandler.handle(deviceCode, payload);
            case DataCollectConstant.MQTT_UP_DEVICE_ONLINE      -> deviceOnlineReportHandler.handle(deviceCode, payload);
            default -> log.warn("[Dispatcher] 未知路径: {}", topic);
        }
    }

    /**
     * 路由 {deviceCode}/master/{path} 或 {deviceCode}/slave/{path} 格式的原始上报 Topic
     *
     * @param deviceCode 设备编码
     * @param role       master（主控）或 slave（机器人）
     * @param path       路径部分（如 cmd / states / rtsp/ctrl）
     * @param payload    原始 Payload（明文 JSON，非加密）
     */
    private void dispatchRawTopic(String deviceCode, String role, String path, String payload) {
        // 机器人原始状态上报：{robotCode}/slave/states
        if ("slave".equals(role) && "states".equals(path)) {
            robotSlaveStatusHandler.handle(deviceCode, payload);
            return;
        }
        // 主控设备原始状态上报：{robotCode}/master/states
        if ("master".equals(role) && "states".equals(path)) {
            robotSlaveStatusHandler.handleMasterStatus(deviceCode, payload);
            return;
        }
        // 主控设备原始状态上报：{masterCode}/master/cmd
        if ("master".equals(role) && "cmd".equals(path)) {
            masterCommandHandler.handle(deviceCode, payload);
            return;
        }
        log.debug("[Dispatcher] 原始上报 deviceCode={} role={} path={}", deviceCode, role, path);
    }

    /** 生成 16 位小写十六进制 spanId，与 Zipkin/Brave 格式一致 */
    private static String generateSpanId() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }
}
