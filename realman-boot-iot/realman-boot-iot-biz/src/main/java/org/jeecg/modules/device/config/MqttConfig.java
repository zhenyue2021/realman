package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
 * <p>僵死恢复说明：
 *   {@link #restartConnection()} 通过 {@code disconnectForcibly + connect} 重建连接，
 *   与 {@code reconnect()} 的区别在于 {@code connect()} 会创建全新的 CommsSender /
 *   CommsReceiver / CommsCallback 线程，彻底消除 Paho 僵死状态。
 *   由 {@link org.jeecg.modules.device.mqtt.MqttClientWatchdog} 检测到僵死后调用。
 *
 * @see MqttConfigContext 延迟获取 MqttMessageDispatcher 的上下文工具类
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttConfig {

    /** 复用同一 MqttClient 对象，通过 connect() 重置内部线程而非创建新对象（避免刷新 Spring bean 引用） */
    private MqttClient client;
    private MqttConnectionOptions savedOpts;
    private String[] savedTopics;
    private int[] savedQosArr;

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
        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

        MqttConnectionOptions opts = new MqttConnectionOptions();
        // cleanStart=true：MQTT 5.0 对应 cleanSession，配合 $share 共享订阅使用。
        // 共享订阅组内只要有存活节点，EMQX 就不会丢消息（故障节点的消息会路由给其他节点）；
        // 每个 Pod 断线重连时无需恢复旧队列，避免 cleanStart=false + 稳定 clientId 时
        // 重连后积压大量离线消息导致消息风暴。
        opts.setCleanStart(true);
        opts.setUserName(username);
        opts.setPassword(password.getBytes(StandardCharsets.UTF_8));
        opts.setKeepAliveInterval(keepAlive);  // 心跳间隔（秒）
        opts.setAutomaticReconnect(true);      // 自动重连（断线后指数退避重试）
        opts.setMaxReconnectDelay(5000);       // 最大重连间隔 5s
        opts.setConnectionTimeout(10);         // TCP 连接超时 10s（默认 30s 太长，快速失败快速重试）
        // TCP Socket 层 keepalive：由 OS 负责探测，早于 MQTT 心跳发现半打开连接。
        // 在 NAT/防火墙静默丢弃 TCP 连接状态时，OS 的 TCP keepalive 能更早探知连接已死。
        opts.setSocketFactory(tcpKeepaliveSocketFactory());

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
        savedOpts = opts;
        final String[] topics = {
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
                share + "device/+/webrtc/restart",          // WebRTC 信令服务启动通知
                share + "device/+/datacollect/collectUrlRequest", // 机器人 → 平台：请求 OSS 上传授权
                share + "device/+/datacollect/ossAdressReport",   // 机器人 → 平台：OSS 文件地址上报
                share + "device/+/datacollect/deviceOnline",      // 机器人 → 平台：设备主动上线上报
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
        savedTopics = topics;
        final int[] qosArr = new int[topics.length];
        Arrays.fill(qosArr, qos);
        savedQosArr = qosArr;

        // 重连后重订阅兜底：Paho 自动重连时会尝试重发内部订阅列表，
        // 但在 cleanStart=true + 某些 Paho 版本下订阅列表可能丢失，此处主动重订阅保证万无一失。
        client.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse response) {
                MqttException cause = response.getException();
                if (cause != null) {
                    log.error("[MQTT] 连接丢失，等待重连", cause);
                } else {
                    log.warn("[MQTT] 连接断开 (reasonCode={})", response.getReturnCode());
                }
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.error("[MQTT] 客户端内部错误", exception);
            }

            @Override
            public void messageArrived(String topic, MqttMessage msg) {
                log.debug("[MQTT] messageArrived: topic={}", topic);
                MqttMessageDispatcher dispatcher = getDispatcher();
                if (dispatcher != null) {
                    dispatcher.dispatch(topic, msg);
                }
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
            }

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                // 重置 Watchdog 计时器：无论是初次连接还是重连，都重新开始计时，
                // 避免 Watchdog 在重订阅完成前因"空闲超时"再次触发强制断连。
                MqttMessageDispatcher.lastReceivedTs.set(System.currentTimeMillis());
                if (!reconnect) return;

                log.info("[MQTT] 重连成功: {}，重新订阅{}个Topic", serverURI, topics.length);
                // 必须在独立线程中调用 subscribe()，不能阻塞当前 Paho CommsCallback 线程。
                // 原因：subscribe() 内部同步等待 SUBACK，而 SUBACK 由 CommsReceiver 接收后
                // 需经 CommsCallback 队列处理。若在 CommsCallback 线程里直接调用，
                // 会造成"CommsCallback 等 SUBACK，SUBACK 等 CommsCallback"的死锁，
                // 超时后 Paho 进入不一致状态（已连接但 messageArrived 永不触发）。
                Thread resubThread = new Thread(() -> {
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        try {
                            client.subscribe(topics, qosArr);
                            log.info("[MQTT] 重订阅完成，共{}个Topic", topics.length);
                            return;
                        } catch (MqttException e) {
                            log.warn("[MQTT] 重订阅第{}次失败", attempt, e);
                            if (attempt < 3) {
                                try {
                                    Thread.sleep(2000L * attempt);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }
                    }
                    log.error("[MQTT] 重订阅3次均失败，等待 Watchdog 触发强制重连");
                }, "mqtt-resubscribe");
                resubThread.setDaemon(true);
                resubThread.start();
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
            }
        });

        client.connect(opts);
        log.info("[MQTT] 平台已连接 EMQX: {}", brokerUrl);

        client.subscribe(topics, qosArr);
        log.info("[MQTT] 已订阅{}个业务Topic", topics.length);
        return client;
    }

    /**
     * 专用发布客户端（仅用于平台 → 设备的指令下发）。
     *
     * <p>与订阅客户端（{@link #mqttClient()}）完全隔离：
     * <ul>
     *   <li>订阅客户端的 CommsCallback 只处理入站 PUBLISH → messageArrived</li>
     *   <li>发布客户端的 CommsCallback 只处理出站 PUBACK 完成回调</li>
     * </ul>
     * 两者互不干扰，彻底消除 Paho v5 中 publish() PUBACK 与 messageArrived 竞争导致
     * CommsCallback 僵死的根本原因。
     *
     * <p>clientId 追加 {@code -pub} 后缀，EMQX 侧需同样放行（与 iot-platform-server 同 username）。
     * 自动重连由 Paho 内置机制负责，无需 Watchdog 监控。
     */
    @Bean("mqttPublishClient")
    @ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
    public MqttClient mqttPublishClient() throws MqttException {
        MqttClient pubClient = new MqttClient(brokerUrl, clientId + "-pub", new MemoryPersistence());

        MqttConnectionOptions pubOpts = new MqttConnectionOptions();
        pubOpts.setCleanStart(true);
        pubOpts.setUserName(username);
        pubOpts.setPassword(password.getBytes(StandardCharsets.UTF_8));
        pubOpts.setKeepAliveInterval(keepAlive);
        pubOpts.setAutomaticReconnect(true);
        pubOpts.setMaxReconnectDelay(5000);
        pubOpts.setConnectionTimeout(10);
        pubOpts.setSocketFactory(tcpKeepaliveSocketFactory());

        pubClient.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse response) {
                MqttException cause = response.getException();
                if (cause != null) log.error("[MQTT-PUB] 连接丢失，等待重连", cause);
                else log.warn("[MQTT-PUB] 连接断开 (reasonCode={})", response.getReturnCode());
            }
            @Override public void mqttErrorOccurred(MqttException exception) {
                log.error("[MQTT-PUB] 客户端内部错误", exception);
            }
            @Override public void messageArrived(String topic, MqttMessage msg) { }
            @Override public void deliveryComplete(IMqttToken token) { }
            @Override public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) log.info("[MQTT-PUB] 重连成功: {}", serverURI);
            }
            @Override public void authPacketArrived(int reasonCode, MqttProperties properties) { }
        });

        pubClient.connect(pubOpts);
        log.info("[MQTT-PUB] 发布客户端已连接 EMQX: {} (clientId={})", brokerUrl, clientId + "-pub");
        return pubClient;
    }

    /**
     * 强制重建 MQTT 连接，彻底消除 Paho 僵死状态。
     *
     * <p>与 {@code reconnect()} 的本质区别：{@code reconnect()} 尝试复用现有的
     * CommsSender/CommsReceiver/CommsCallback 线程，在僵死场景下这些线程已损坏；
     * 本方法调用 {@code connect(opts)} ，Paho 内部会创建全新的通信线程，彻底恢复。
     *
     * <p>使用同一 {@link MqttClient} 对象（而非创建新对象），避免刷新已注入其他组件的 Bean 引用。
     * {@link org.jeecg.modules.device.mqtt.publisher.MqttPublisher} 通过
     * {@code ObjectProvider.getIfAvailable()} 每次获取，重建后自动使用恢复的连接。
     *
     * <p>本方法已加锁，Watchdog 多次触发时串行执行不会并发冲突。
     */
    public synchronized void restartConnection() {
        log.warn("[MQTT] 开始重建连接（disconnectForcibly → connect，创建全新 Paho 通信线程）...");
        // 重置计时器，避免重建期间 Watchdog 再次触发
        MqttMessageDispatcher.lastReceivedTs.set(System.currentTimeMillis());
        try {
            client.disconnectForcibly(0L, 3000L);
        } catch (Exception e) {
            log.warn("[MQTT] 强制断开异常（忽略，继续重连）", e);
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                client.connect(savedOpts);
                client.subscribe(savedTopics, savedQosArr);
                log.info("[MQTT] 连接重建完成，已重新订阅 {} 个 Topic", savedTopics.length);
                return;
            } catch (MqttException e) {
                log.warn("[MQTT] 重建连接第 {} 次失败: {}", attempt, e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("[MQTT] 重建连接 3 次均失败，等待下次 Watchdog 触发");
    }

    public boolean isClientConnected() {
        return client != null && client.isConnected();
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

    /**
     * 创建启用 TCP SO_KEEPALIVE 的 SocketFactory。
     * OS 层面的 TCP keepalive 早于 MQTT 心跳探测到半打开连接（NAT/防火墙静默丢弃 TCP 状态时尤为有效）。
     * 具体探测间隔由 OS 内核参数控制：tcp_keepalive_time（默认2h，建议调低至300s）。
     */
    private static SocketFactory tcpKeepaliveSocketFactory() {
        return new SocketFactory() {
            @Override
            public Socket createSocket() throws IOException {
                Socket s = SocketFactory.getDefault().createSocket();
                s.setKeepAlive(true);
                return s;
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                Socket s = SocketFactory.getDefault().createSocket(host, port);
                s.setKeepAlive(true);
                return s;
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
                Socket s = SocketFactory.getDefault().createSocket(host, port, localHost, localPort);
                s.setKeepAlive(true);
                return s;
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                Socket s = SocketFactory.getDefault().createSocket(host, port);
                s.setKeepAlive(true);
                return s;
            }

            @Override
            public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                Socket s = SocketFactory.getDefault().createSocket(address, port, localAddress, localPort);
                s.setKeepAlive(true);
                return s;
            }
        };
    }
}
