package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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

    /** 匹配 device/{deviceCode}/{path}，group(1)=deviceCode，group(2)=path */
    private static final Pattern DEVICE_TOPIC     = Pattern.compile("^device/([^/]+)/(.+)$");
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

    /**
     * 分发 MQTT 消息到对应 Handler
     *
     * @param topic   MQTT Topic 字符串
     * @param message MQTT 消息对象（Payload 为 AES 加密密文或原始 JSON）
     */
    public void dispatch(String topic, MqttMessage message) {
        // 标准化：去除前导 /，兼容部分设备固件将 topic 写为 /code/slave/states 的情况
        if (topic != null && topic.startsWith("/")) {
            topic = topic.substring(1);
        }
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
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

            // 3. WebRTC 指令 ACK：webrtc/{deviceCode}/command/{start|stop}/ack
            if (topic.startsWith("webrtc/") && topic.contains("/command/") && topic.endsWith("/ack")) {
                String[] parts = topic.split("/");
                if (parts.length == 5) {
                    // parts: ["webrtc", masterCode, "command", cmd, "ack"]
                    webRtcAckHandler.handle(parts[1], parts[3], payload);
                    return;
                }
            }

            // 4. 主控/机器人原始上报 Topic：{deviceCode}/master/{path} 或 {deviceCode}/slave/{path}
            Matcher raw = RAW_DEVICE_TOPIC.matcher(topic);
            if (raw.matches()) {
                dispatchRawTopic(raw.group(1), raw.group(2), raw.group(3), payload);
                return;
            }

            log.warn("[Dispatcher] 未识别Topic: {}", topic);
        } catch (Exception e) {
            log.error("[Dispatcher] 处理消息异常 topic={}", topic, e);
        }
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
}
