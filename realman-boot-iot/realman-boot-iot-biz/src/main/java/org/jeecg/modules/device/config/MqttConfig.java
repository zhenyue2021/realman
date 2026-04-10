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

    /**
     * clientId 基础值，部署时通过环境变量或配置项为每个 Pod 设置唯一稳定的 ID。
     *
     * <p>单机模式：保持默认值 {@code iot-platform-server} 即可。
     * <p>集群模式（配合 $share 共享订阅）：每个 Pod 须配置不同的稳定 clientId，
     * 例如在 Kubernetes 中注入 Pod 名：
     * <pre>
     *   env:
     *     - name: MQTT_CLIENT_ID
     *       valueFrom:
     *         fieldRef:
     *           fieldPath: metadata.name   # 如 iot-platform-6d7f9b-xk2p1
     * </pre>
     * application.yml：{@code mqtt.broker.client-id: ${MQTT_CLIENT_ID:iot-platform-server}}
     *
     * <p>注意：集群模式下 clientId 必须稳定（不能追加随机/时间戳后缀），否则每次重启都在
     * EMQX 创建新的持久 Session，造成 Session 泄漏。
     * 单机模式下若不配置 {@code MQTT_CLIENT_ID}，保持默认值稳定即可。
     */
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
        // cleanSession=true：配合 $share 共享订阅使用。
        // 共享订阅组内只要有存活节点，EMQX 就不会丢消息（故障节点的消息会路由给其他节点）；
        // 每个 Pod 断线重连时无需恢复旧队列，避免 cleanSession=false + 稳定 clientId 时
        // 重连后积压大量离线消息导致消息风暴。
        opts.setCleanSession(true);
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
        //
        // 说明：
        //   - 纯上报型 Topic（状态/日志/OTA 等）：使用 $share/iot-cluster/ 前缀，集群中只有一个节点处理，
        //     避免 N 个节点同时写库/写 Redis。
        //   - 请求-响应型 Topic（camera/ack、command/+/ack、slam/ack 等）：同样使用共享订阅，
        //     但这些 Topic 的 Handler 会通过 Redis Pub/Sub 将结果广播到持有 CompletableFuture 的节点，
        //     见 RedisPendingListenerConfig。
        //   - $SYS 系统事件：EMQX 不支持 $share 订阅，保持原样（所有节点均接收）；
        //     Handler 内部通过幂等设计（Redis SET NX）保证只有一个节点实际处理。
        //   - 带前导 / 的原始 Topic：兼容旧固件，无需共享订阅（流量极低）。
        String share = "$share/iot-cluster/";
        String[] topics = {
                share + "device/+/status/report",           // 设备状态上报
                share + "device/+/config/ack",              // 参数配置同步确认
                share + "device/+/command/+/ack",           // 指令集执行确认（restart/emergency-stop 等）
                share + "master/+/command/+/ack",           // 主控端指令集执行确认（力反馈/底盘速度等）
                share + "device/+/ota/progress",            // OTA 升级进度上报
                share + "device/+/log/operation",           // 设备操作日志上报
                share + "device/+/camera/stream/ack",       // 机器人上报摄像头视频流地址
                share + "master/+/teleop/associated-device/ack", // 主控上报当前关联设备信息
                share + "device/+/slam/upload/request",     // 机器人请求SLAM上传许可
                share + "device/+/slam/upload/complete",    // 机器人通知SLAM上传完成
                share + "device/+/slam/sync/ack",           // 机器人回传SLAM同步结果
                share + "device/+/slam/ack",                // 机器人响应建图/定位/导航指令
                share + "device/+/slam/states",             // 机器人上报SLAM状态及位姿
                share + "device/+/ext-params/request",      // 设备请求外部系统服务参数
                share + "device/+/webrtc/ack",              // 机器人回复 WebRTC 指令 ACK
                "$SYS/brokers/+/clients/+/connected",       // EMQX 设备上线事件（不支持共享订阅）
                "$SYS/brokers/+/clients/+/disconnected",    // EMQX 设备下线事件（不支持共享订阅）
                // ========== 主控/机器人原始上报（带前导 / 兼容旧固件） ==========
//                share + "+/master/cmd",
//                share + "/+/master/cmd",
                share + "+/master/states",
                share + "/+/master/states",
//                share + "+/master/rtsp/ctrl",
//                share + "/+/master/rtsp/ctrl",
//                share + "+/slave/cmd",
//                share + "/+/slave/cmd",
                share + "+/slave/states",
                share + "/+/slave/states",
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
