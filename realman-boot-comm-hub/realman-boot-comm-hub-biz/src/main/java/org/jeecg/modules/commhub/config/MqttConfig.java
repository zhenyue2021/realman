package org.jeecg.modules.commhub.config;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.jeecg.modules.commhub.contract.constant.CommHubTopicConstants;
import org.jeecg.modules.commhub.mqtt.MqttMessageDispatcher;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备通信中台独立 MQTT 客户端连接配置。对齐设备通信中台详细设计 2.2/2.3：南向
 * 唯一协议是 MQTT（自注册除外），本类负责与 EMQX 建立连接、订阅设备端向 Topic。
 *
 * <p>沿用一个成熟的既有架构决策（与 realman-boot-iot 独立实现同一模式，互不引用）：
 * 订阅与发布使用两个独立 {@link MqttClient} 实例，避免 PUBACK 的
 * {@code deliveryComplete} 回调与 {@code messageArrived} 回调在同一个 Paho 内部
 * 回调线程上互相竞争。订阅端关闭 {@code automaticReconnect}，改由
 * {@link org.jeecg.modules.commhub.mqtt.MqttClientWatchdog} 主动检测并重建连接
 * （能应对"连接状态显示正常但内部回调线程已僵死"的场景，仅靠 Paho 自动重连无法覆盖）；
 * 发布端保留自动重连（该客户端只发布不订阅，不涉及僵死回调线程的问题）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MqttConfig implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** 自检心跳 Topic，不使用共享订阅，验证发布-&gt;broker-&gt;订阅-&gt;回调全链路是否存活 */
    public static final String HEARTBEAT_TOPIC = "comm-hub/heartbeat";

    private final MqttClientProperties properties;
    private final MqttMessageDispatcher dispatcher;

    private volatile List<String> savedTopics;
    private volatile int[] savedQos;

    @Bean
    @Lazy
    public MqttClient mqttSubscribeClient() throws MqttException {
        String clientId = properties.getClientIdPrefix() + "-sub-" + IdUtil.fastSimpleUUID().substring(0, 8);
        MqttClient client = new MqttClient(properties.getBrokerUrl(), clientId, new MemoryPersistence());
        client.setCallback(subscribeCallback());
        return client;
    }

    @Bean
    @Lazy
    public MqttClient mqttPublishClient() throws MqttException {
        String clientId = properties.getClientIdPrefix() + "-pub-" + IdUtil.fastSimpleUUID().substring(0, 8);
        MqttClient client = new MqttClient(properties.getBrokerUrl(), clientId, new MemoryPersistence());
        client.setCallback(noopCallback());
        return client;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            connectSubscribeClient();
            connectPublishClient();
        } catch (MqttException e) {
            log.error("[comm-hub] MQTT 初始连接失败，等待 Watchdog 后续重试: {}", e.getMessage(), e);
        }
    }

    private void connectSubscribeClient() throws MqttException {
        MqttClient client = mqttSubscribeClient();
        MqttConnectionOptions options = baseOptions();
        options.setAutomaticReconnect(false);
        client.connect(options);

        List<String> topics = buildSubscribeTopics();
        int[] qos = new int[topics.size()];
        for (int i = 0; i < qos.length; i++) {
            qos[i] = properties.getDefaultQos();
        }
        savedTopics = topics;
        savedQos = qos;
        subscribeAll(client, topics, qos);
        log.info("[comm-hub] MQTT 订阅客户端已连接，共订阅 {} 个 Topic", topics.size());
    }

    private void connectPublishClient() throws MqttException {
        MqttClient client = mqttPublishClient();
        MqttConnectionOptions options = baseOptions();
        options.setAutomaticReconnect(true);
        client.connect(options);
        log.info("[comm-hub] MQTT 发布客户端已连接");
    }

    private void subscribeAll(MqttClient client, List<String> topics, int[] qos) throws MqttException {
        IMqttToken token = client.subscribe(topics.toArray(new String[0]), qos);
        token.waitForCompletion(properties.getConnectionTimeoutSeconds() * 1000L);
        int[] reasonCodes = token.getReasonCodes();
        if (reasonCodes != null) {
            for (int i = 0; i < reasonCodes.length && i < topics.size(); i++) {
                if (reasonCodes[i] >= 0x80) {
                    log.warn("[comm-hub] Topic 订阅失败 topic={} reasonCode={}", topics.get(i), reasonCodes[i]);
                }
            }
        }
    }

    private List<String> buildSubscribeTopics() {
        String shared = "$share/" + properties.getSharedSubscriptionGroup() + "/";
        List<String> topics = new ArrayList<>();
        topics.add(shared + "device/+/" + CommHubTopicConstants.TOPIC_STATUS_REPORT);
        topics.add(shared + "device/+/" + CommHubTopicConstants.TOPIC_OTA_PROGRESS);
        topics.add(shared + "device/+/" + CommHubTopicConstants.TOPIC_OTA_STATUS_REPORT);
        topics.add(shared + "device/+/" + CommHubTopicConstants.TOPIC_OTA_TOKEN_REFRESH);
        topics.add(shared + "device/+/" + CommHubTopicConstants.TOPIC_BRIDGE_ACK);
        // $SYS 连接/断开事件不支持共享订阅，每个节点都会收到，靠 Redis SETNX 做跨节点幂等（见 MqttMessageDispatcher）
        topics.add("$SYS/brokers/+/clients/+/connected");
        topics.add("$SYS/brokers/+/clients/+/disconnected");
        // 自检心跳，不使用共享订阅——每个节点都需要收到自己发出的心跳，验证发布->broker->订阅->回调全链路是否存活
        topics.add(HEARTBEAT_TOPIC);
        return topics;
    }

    private MqttConnectionOptions baseOptions() {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setUserName(properties.getUsername());
        options.setPassword(properties.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        options.setKeepAliveInterval(properties.getKeepAliveSeconds());
        options.setConnectionTimeout(properties.getConnectionTimeoutSeconds());
        options.setMaxReconnectDelay(5000);
        return options;
    }

    private MqttCallback subscribeCallback() {
        return new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                log.warn("[comm-hub] MQTT 订阅客户端断开: {}", disconnectResponse.getReasonString());
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.warn("[comm-hub] MQTT 订阅客户端错误: {}", exception.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                dispatcher.dispatch(topic, new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
                // 订阅端不发布消息，无需处理
            }

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("[comm-hub] MQTT 订阅客户端 connectComplete reconnect={} serverURI={}", reconnect, serverURI);
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
                // 本服务未启用增强鉴权（AUTH 报文），无需处理
            }
        };
    }

    private MqttCallback noopCallback() {
        return new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                log.warn("[comm-hub] MQTT 发布客户端断开: {}", disconnectResponse.getReasonString());
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.warn("[comm-hub] MQTT 发布客户端错误: {}", exception.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // 发布端不订阅任何 Topic，理论上不会触发
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
                // PUBACK 完成即可，无需业务处理；应用层 ACK 走独立的设备回执 Topic
            }

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("[comm-hub] MQTT 发布客户端 connectComplete reconnect={} serverURI={}", reconnect, serverURI);
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
                // 本服务未启用增强鉴权（AUTH 报文），无需处理
            }
        };
    }

    /**
     * 重建订阅连接：{@link org.jeecg.modules.commhub.mqtt.MqttClientWatchdog} 检测到
     * 空闲超时或断线时调用。用 {@code disconnectForcibly + connect} 而非 Paho 自带的
     * {@code reconnect()}——后者会尝试复用可能已经僵死的内部通信线程。
     */
    public synchronized void restartSubscribeConnection() {
        MqttException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                MqttClient client = mqttSubscribeClient();
                try {
                    client.disconnectForcibly(0L, 3000L);
                } catch (MqttException ignored) {
                    // 断开失败不影响后续重连尝试
                }
                MqttConnectionOptions options = baseOptions();
                options.setAutomaticReconnect(false);
                client.connect(options);
                if (savedTopics != null && savedQos != null) {
                    subscribeAll(client, savedTopics, savedQos);
                }
                log.info("[comm-hub] MQTT 订阅连接重建成功，尝试次数={}", attempt);
                return;
            } catch (MqttException e) {
                lastError = e;
                log.warn("[comm-hub] MQTT 订阅连接重建失败，尝试次数={}: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("[comm-hub] MQTT 订阅连接重建三次尝试均失败", lastError);
    }

    /** 发布自检心跳，供 {@link org.jeecg.modules.commhub.mqtt.MqttClientWatchdog} 定时调用。 */
    public void publishHeartbeat() {
        try {
            MqttMessage message = new MqttMessage(("{\"ts\":" + System.currentTimeMillis() + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setQos(0);
            message.setRetained(false);
            mqttPublishClient().publish(HEARTBEAT_TOPIC, message);
        } catch (MqttException e) {
            log.warn("[comm-hub] 自检心跳发布失败: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() throws Exception {
        try {
            mqttSubscribeClient().disconnectForcibly(0L, 3000L);
        } catch (Exception ignored) {
            // 关闭阶段忽略断连异常
        }
        try {
            mqttPublishClient().disconnectForcibly(0L, 3000L);
        } catch (Exception ignored) {
            // 关闭阶段忽略断连异常
        }
    }
}
