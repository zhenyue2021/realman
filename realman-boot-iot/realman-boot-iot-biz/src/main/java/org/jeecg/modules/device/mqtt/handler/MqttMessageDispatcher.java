package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT消息分发器
 * 鉴权已在EMQX连接层完成，此处直接按Topic路径路由到业务Handler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttMessageDispatcher {

    private static final Pattern DEVICE_TOPIC = Pattern.compile("^device/([^/]+)/(.+)$");

    private final DeviceStatusHandler       statusHandler;
    private final DeviceConfigAckHandler    configAckHandler;
    private final DeviceRestartAckHandler   restartAckHandler;
    private final OtaProgressHandler        otaProgressHandler;
    private final DeviceOperationLogHandler operationLogHandler;
    private final DeviceOnlineOfflineHandler onlineOfflineHandler;

    public void dispatch(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            if (topic.contains("/clients/") && topic.contains("/connected")) {
                onlineOfflineHandler.handleOnline(topic, payload); return;
            }
            if (topic.contains("/clients/") && topic.contains("/disconnected")) {
                onlineOfflineHandler.handleOffline(topic, payload); return;
            }
            Matcher m = DEVICE_TOPIC.matcher(topic);
            if (!m.matches()) { log.warn("[Dispatcher] 未识别Topic: {}", topic); return; }
            String deviceCode = m.group(1);
            String path       = m.group(2);
            switch (path) {
                case "status/report"       -> statusHandler.handle(deviceCode, payload);
                case "config/ack"          -> configAckHandler.handle(deviceCode, payload);
                case "command/restart/ack" -> restartAckHandler.handle(deviceCode, payload);
                case "ota/progress"        -> otaProgressHandler.handle(deviceCode, payload);
                case "log/operation"       -> operationLogHandler.handle(deviceCode, payload);
                default -> log.warn("[Dispatcher] 未知路径: {}", topic);
            }
        } catch (Exception e) {
            log.error("[Dispatcher] 处理消息异常 topic={}", topic, e);
        }
    }
}
