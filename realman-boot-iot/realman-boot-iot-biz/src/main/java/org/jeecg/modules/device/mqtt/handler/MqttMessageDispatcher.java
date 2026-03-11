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
 *   $SYS/.../clients/.../connected    → DeviceOnlineOfflineHandler.handleOnline()
 *   $SYS/.../clients/.../disconnected → DeviceOnlineOfflineHandler.handleOffline()
 *   device/{code}/status/report       → DeviceStatusHandler.handle()
 *   device/{code}/config/ack          → DeviceConfigAckHandler.handle()
 *   device/{code}/command/{cmd}/ack   → DeviceCommandAckHandler.handle()
 *   device/{code}/ota/progress        → OtaProgressHandler.handle()
 *   device/{code}/log/operation       → DeviceOperationLogHandler.handle()
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

    /** 匹配 device/{deviceCode}/{path} 格式的业务 Topic，group(1)=deviceCode，group(2)=path */
    private static final Pattern DEVICE_TOPIC = Pattern.compile("^device/([^/]+)/(.+)$");

    private final DeviceStatusHandler statusHandler;
    private final DeviceConfigAckHandler configAckHandler;
    private final DeviceCommandAckHandler commandAckHandler;
    private final OtaProgressHandler otaProgressHandler;
    private final DeviceOperationLogHandler operationLogHandler;
    private final DeviceOnlineOfflineHandler onlineOfflineHandler;

    /**
     * 分发 MQTT 消息到对应 Handler
     *
     * <p>执行流程：
     * <ol>
     *   <li>将 Payload 字节数组转为 UTF-8 字符串（此时仍为密文）</li>
     *   <li>优先判断 $SYS 系统事件 Topic（上下线事件，无设备前缀）</li>
     *   <li>用正则匹配 device/{deviceCode}/{path}，提取 deviceCode 和 path</li>
     *   <li>switch 路由到对应 Handler，各 Handler 内部负责解密和业务处理</li>
     *   <li>捕获所有异常并记录 ERROR 日志，避免单条消息异常影响整体消费</li>
     * </ol>
     *
     * @param topic   MQTT Topic 字符串
     * @param message MQTT 消息对象（Payload 为 AES 加密密文）
     */
    public void dispatch(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            // 1. 优先处理 EMQX $SYS 系统事件（设备上下线），这类 Topic 不符合 device/xxx 格式
            if (topic.contains("/clients/") && topic.contains("/connected")) {
                onlineOfflineHandler.handleOnline(topic, payload);
                return;
            }
            if (topic.contains("/clients/") && topic.contains("/disconnected")) {
                onlineOfflineHandler.handleOffline(topic, payload);
                return;
            }

            // 2. 匹配业务 Topic：device/{deviceCode}/{path}
            Matcher m = DEVICE_TOPIC.matcher(topic);
            if (!m.matches()) {
                log.warn("[Dispatcher] 未识别Topic: {}", topic);
                return;
            }
            String deviceCode = m.group(1);
            String path       = m.group(2);

            // 3. 按 path 路由到对应业务 Handler（各 Handler 内部解密并处理）
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
                case "status/report" -> statusHandler.handle(deviceCode, payload);
                case "config/ack" -> configAckHandler.handle(deviceCode, payload);
                case "ota/progress" -> otaProgressHandler.handle(deviceCode, payload);
                case "log/operation" -> operationLogHandler.handle(deviceCode, payload);
                // TODO 待实现 后增加的topic的处理逻辑
                default -> log.warn("[Dispatcher] 未知路径: {}", topic);
            }
        } catch (Exception e) {
            // 捕获所有异常，防止单消息处理失败阻塞 MQTT 线程
            log.error("[Dispatcher] 处理消息异常 topic={}", topic, e);
        }
    }
}
