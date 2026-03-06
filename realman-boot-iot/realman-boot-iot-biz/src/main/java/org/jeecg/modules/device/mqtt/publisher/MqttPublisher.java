package org.jeecg.modules.device.mqtt.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttPublisher {

    private final MqttClient mqttClient;
    private final CommandEncryptService encryptService;

    /** 向设备发布AES加密消息 */
    public void publishToDevice(String deviceCode, String topic, String payload, int qos) {
        String encrypted = encryptService.encryptForDevice(deviceCode, payload);
        publishRaw(topic, encrypted, qos, false);
    }

    public void publishRaw(String topic, String payload, int qos, boolean retained) {
        try {
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
