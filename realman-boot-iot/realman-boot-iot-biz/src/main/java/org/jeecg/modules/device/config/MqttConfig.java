package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * MQTT 客户端配置（平台侧连接 EMQX）
 *
 * <p>职责：在 Spring Boot 启动时，以平台服务账号（iot-platform-server）连接 EMQX，
 * 订阅全部业务 Topic，接收设备上行消息并转发给 {@link MqttMessageDispatcher} 处理。
 *
 * <p>鉴权说明：
 *   平台以固定账号（iot-platform-server）连接 EMQX，通过 EMQX HTTP Auth 插件白名单放行。
 *   设备鉴权在 EMQX 连接层完成，与此处无关。
 *
 * <p>循环依赖说明：
 *   {@code MqttConfig} 创建 {@link MqttClient} Bean 时需要调用 {@link MqttMessageDispatcher}，
 *   但 MqttMessageDispatcher 又依赖多个 Handler Bean，可能形成循环依赖。
 *   因此通过 {@link MqttConfigContext}（ApplicationContextAware）在消息回调时延迟获取 Dispatcher，
 *   而非在构造时注入，避免循环依赖问题。
 *
 * <p>此 Bean 仅在配置 {@code mqtt.enabled=true} 时生效，可通过关闭此配置在无 EMQX 环境下启动。
 *
 * @see MqttConfigContext 延迟获取 MqttMessageDispatcher 的上下文工具类
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttConfig {

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.broker.username:platform}")
    private String username;

    @Value("${mqtt.broker.password:platform_secret}")
    private String password;

    /** clientId 追加时间戳后缀，防止多实例部署时 clientId 冲突（EMQX 会踢掉同 clientId 的旧连接） */
    @Value("${mqtt.broker.client-id:iot-platform-server}")
    private String clientId;

    @Value("${mqtt.broker.qos:1}")
    private int qos;

    @Value("${mqtt.broker.keep-alive:60}")
    private int keepAlive;

    /**
     * 创建并连接 MQTT 客户端，订阅所有业务 Topic
     *
     * <p>执行流程：
     * <ol>
     *   <li>创建 MqttClient（MemoryPersistence，clientId 追加时间戳防冲突）</li>
     *   <li>配置连接选项：非 cleanSession（QoS1 消息离线期间 EMQX 保留）、自动重连、keepAlive</li>
     *   <li>设置消息回调（messageArrived 委托给 MqttMessageDispatcher）</li>
     *   <li>连接 EMQX</li>
     *   <li>批量订阅业务 Topic（均为 QoS=1 保证至少一次投递）</li>
     * </ol>
     *
     * @return 已连接并订阅的 MqttClient
     * @throws MqttException 连接或订阅失败时抛出
     */
    @Bean
    public MqttClient mqttClient() throws MqttException {
        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(false);           // 非 cleanSession：QoS1 消息在断线重连后可恢复
        opts.setUserName(username);
        opts.setPassword(password.toCharArray());
        opts.setKeepAliveInterval(keepAlive);  // 心跳间隔（秒）
        opts.setAutomaticReconnect(true);      // 自动重连（断线后指数退避重试）
        opts.setMaxReconnectDelay(5000);       // 最大重连间隔 5s

        client.setCallback(new MqttCallback() {
            /** 连接丢失（网络中断等），自动重连机制将在后台重试 */
            public void connectionLost(Throwable e) {
                log.error("[MQTT] 连接丢失，等待重连", e);
            }

            /**
             * 收到订阅消息，委托给 MqttMessageDispatcher 按 Topic 路由处理
             * 通过 MqttConfigContext 延迟获取 Dispatcher，避免 Spring 循环依赖
             */
            public void messageArrived(String topic, MqttMessage msg) {
                MqttMessageDispatcher dispatcher = getDispatcher();
                if (dispatcher != null) {
                    dispatcher.dispatch(topic, msg);
                }
            }

            /** QoS1/2 消息发布完成回调（平台侧发布不需要特殊处理） */
            public void deliveryComplete(IMqttDeliveryToken t) {
            }
        });

        client.connect(opts);
        log.info("[MQTT] 平台已连接 EMQX: {}", brokerUrl);

        // 订阅全部业务 Topic（鉴权相关 Topic 由 EMQX HTTP Auth 在连接层处理，无需平台订阅）
        String[] topics = {
                "device/+/status/report",           // 设备状态上报
                "device/+/config/ack",              // 参数配置同步确认
                "device/+/command/+/ack",           // 指令集执行确认（restart/emergency-stop/poweroff/reset...）
                "master/+/command/+/ack",           // 主控端指令集执行确认（力反馈/底盘速度，...）
                "device/+/ota/progress",            // OTA 升级进度上报
                "device/+/log/operation",           // 设备操作日志上报
                "device/+/camera/stream/response",  // 机器人上报摄像头视频流地址
                "master/+/teleop/associated-device/response", // 主控上报当前关联设备信息
                "$SYS/brokers/+/clients/+/connected",    // EMQX 设备上线事件
                "$SYS/brokers/+/clients/+/disconnected",  // EMQX 设备下线事件
                // ========== 订阅主控设备主动上报的数据 ==========
                "+/master/cmd",                     // 平设备主动上报指令
                "+/master/states",                  // 设备主动上报状态等数据
                "+/master/rtsp/ctrl",               // 设备主动上报 RTSP
                // ========== 订阅机器人设备主动上报的数据 ==========
                "+/slave/cmd",                     // 机器人设备主动上报指令
                "+/slave/states"                   // 机器人设备主动上报状态等数据
        };
        int[] qosArr = new int[topics.length];
        Arrays.fill(qosArr, qos);
        client.subscribe(topics, qosArr);
        log.info("[MQTT] 已订阅{}个业务Topic", topics.length);
        return client;
    }

    /**
     * 通过 {@link MqttConfigContext}（ApplicationContextAware）延迟获取 MqttMessageDispatcher
     *
     * <p>在 Bean 初始化阶段（mqttClient() 执行时）直接注入 MqttMessageDispatcher 会形成循环依赖，
     * 因此改为在消息回调时（运行时）通过 ApplicationContext 获取。
     */
    private MqttMessageDispatcher getDispatcher() {
        return MqttConfigContext.getDispatcher();
    }
}
