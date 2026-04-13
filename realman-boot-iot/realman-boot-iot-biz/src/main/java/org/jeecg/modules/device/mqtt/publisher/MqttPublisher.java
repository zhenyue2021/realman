package org.jeecg.modules.device.mqtt.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.jeecg.common.trace.TraceIdConst;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttPublisher {

    private final ObjectProvider<MqttClient> mqttClientProvider;
    private final CommandEncryptService encryptService;

    /** 向设备发布AES加密消息 */
    public void publishToDevice(String deviceCode, String topic, String payload, int qos) {
        log.debug("[MqttPublisher] -解密前- 发送消息给设备: topic={}, payload={}", topic, payload);
        String encrypted = encryptService.encryptForDevice(deviceCode, payload);
        log.info("[MqttPublisher] -解密后- 发送消息给设备: topic={}, payload={}", topic, encrypted);
        publishRaw(topic, encrypted, qos, false);
    }

    public void publishRaw(String topic, String payload, int qos, boolean retained) {
        try {
            MqttClient mqttClient = mqttClientProvider.getIfAvailable();
            if (mqttClient == null) {
                log.warn("[MqttPublisher] 未启用MQTT或未创建MqttClient，跳过发布: topic={}", topic);
                return;
            }
            if (!mqttClient.isConnected()) {
                log.warn("[MqttPublisher] MQTT未连接, topic={}", topic);
                return;
            }
            MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            msg.setQos(qos);
            msg.setRetained(retained);
            String traceId = MDC.get(TraceIdConst.MDC_TRACE_ID);
            if (StringUtils.hasText(traceId)) {
                MqttProperties props = new MqttProperties();
                List<UserProperty> userProps = new ArrayList<>();
                userProps.add(new UserProperty(TraceIdConst.HEADER_TRACE_ID, traceId));
                props.setUserProperties(userProps);
                msg.setProperties(props);
            }
            mqttClient.publish(topic, msg);
        } catch (MqttException e) {
            throw new RuntimeException("MQTT发布失败: " + e.getMessage(), e);
        }
    }
}
