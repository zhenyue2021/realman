package org.jeecg.modules.commhub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 设备通信中台独立 MQTT 客户端连接配置。本服务自建一套连接 EMQX 的 MQTT 客户端，
 * 不复用 {@code realman-boot-iot} 的既有连接（两者是独立部署的服务）。
 *
 * <pre>
 * comm-hub:
 *   mqtt:
 *     broker-url: tcp://emqx:1883
 *     username: comm-hub-service
 *     password: change-me
 *     shared-subscription-group: comm-hub-cluster
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "comm-hub.mqtt")
public class MqttClientProperties {

    private String brokerUrl = "tcp://localhost:1883";

    private String clientIdPrefix = "comm-hub";

    /** 服务账号，用于以超管身份订阅全量设备 Topic 与 $SYS 事件，见 EMQX 鉴权回调 allowPlatformSuperuser 约定 */
    private String username = "comm-hub-service";

    private String password = "change-me-in-production";

    private int keepAliveSeconds = 60;

    private int connectionTimeoutSeconds = 10;

    /** EMQX 共享订阅组名，保证每条纯上报类消息只被集群中一个节点处理一次 */
    private String sharedSubscriptionGroup = "comm-hub-cluster";

    private int defaultQos = 1;

    /** Watchdog：消息接收静默超过该时长视为连接僵死，触发重连 */
    private int idleTimeoutSeconds = 90;

    private long restartDebounceMs = 30_000L;

    private int heartbeatIntervalSeconds = 45;

    private int watchdogCheckIntervalSeconds = 30;

    /** publish-and-wait 默认超时（毫秒），请求未显式指定 ackTimeoutMs 时使用 */
    private long defaultAckTimeoutMs = 10_000L;
}
