package org.jeecg.modules.device.mqtt.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

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
            mqttClient.publish(topic, msg);
        } catch (MqttException e) {
            throw new RuntimeException("MQTT发布失败: " + e.getMessage(), e);
        }
    }
}
