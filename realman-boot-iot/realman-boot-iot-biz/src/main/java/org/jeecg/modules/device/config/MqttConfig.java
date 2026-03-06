package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Arrays;

/**
 * MQTT客户端配置（平台侧连接EMQX）
 *
 * 设备鉴权说明：
 *   鉴权在EMQX连接层完成，由HTTP Auth插件回调 /internal/mqtt/auth。
 *   平台侧不订阅auth相关Topic，仅订阅业务数据Topic。
 */
@Slf4j
@Configuration
public class MqttConfig {

    @Value("${mqtt.broker.url:tcp://localhost:1883}")       private String  brokerUrl;
    @Value("${mqtt.broker.username:platform}")              private String  username;
    @Value("${mqtt.broker.password:platform_secret}")       private String  password;
    @Value("${mqtt.broker.client-id:iot-platform-server}")  private String  clientId;
    @Value("${mqtt.broker.qos:1}")                          private int     qos;
    @Value("${mqtt.broker.keep-alive:60}")                  private int     keepAlive;

    @Bean
    public MqttClient mqttClient(MqttMessageDispatcher dispatcher) throws MqttException {
        MqttClient client = new MqttClient(brokerUrl,
                clientId + "-" + System.currentTimeMillis(), new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(false);
        opts.setUserName(username);
        opts.setPassword(password.toCharArray());
        opts.setKeepAliveInterval(keepAlive);
        opts.setAutomaticReconnect(true);
        opts.setMaxReconnectDelay(5000);

        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable e) { log.error("[MQTT] 连接丢失，等待重连", e); }
            public void messageArrived(String topic, MqttMessage msg) { dispatcher.dispatch(topic, msg); }
            public void deliveryComplete(IMqttDeliveryToken t) {}
        });

        client.connect(opts);
        log.info("[MQTT] 平台已连接 EMQX: {}", brokerUrl);

        // 订阅业务Topic（鉴权Topic由EMQX HTTP Auth插件在连接层处理，无需订阅）
        String[] topics = {
            "device/+/status/report",
            "device/+/config/ack",
            "device/+/command/restart/ack",
            "device/+/ota/progress",
            "device/+/log/operation",
            "$SYS/brokers/+/clients/+/connected",
            "$SYS/brokers/+/clients/+/disconnected"
        };
        int[] qosArr = new int[topics.length];
        Arrays.fill(qosArr, qos);
        client.subscribe(topics, qosArr);
        log.info("[MQTT] 已订阅{}个业务Topic", topics.length);
        return client;
    }
}
