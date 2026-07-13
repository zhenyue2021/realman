package org.jeecg.modules.commhub.contract.constant;

/**
 * 设备端向 MQTT Topic 规范。对齐设备通信中台详细设计 2.2；Topic 一律采用
 * {@code device/{deviceCode}/{子路径}} 模式（历史遗留的 {@code {code}/master|slave/...}
 * 等形式仅做兼容保留，不在本常量类中新增）。
 *
 * <p>本类只收录跨服务/跨模块需要引用的 Topic 后缀（即消费方是通信中台之外的
 * 业务服务），纯内部使用的 Topic（如平台自检）留在通信中台 biz 实现内部定义。
 */
public final class CommHubTopicConstants {

    private CommHubTopicConstants() {
    }

    /** 设备状态上报（上行），驱动 heartbeat-snapshot 同步。 */
    public static final String TOPIC_STATUS_REPORT = "status/report";

    /** OTA 升级通知（下行）。 */
    public static final String TOPIC_OTA_NOTIFY = "ota/notify";

    /** OTA 升级进度（上行）。 */
    public static final String TOPIC_OTA_PROGRESS = "ota/progress";

    /** 设备心跳（上行，含资源信息），语义对齐 OTA PRD 心跳接口。 */
    public static final String TOPIC_OTA_HEARTBEAT = "ota/heartbeat";

    /** OTA 离线状态补传（上行）。 */
    public static final String TOPIC_OTA_STATUS_REPORT = "ota/status-report";

    /** Device Token 续签（上行携带旧 Token，下行返回新 Token）。 */
    public static final String TOPIC_OTA_TOKEN_REFRESH = "ota/token-refresh";

    /**
     * OTA 资源探测（下行发起 + 上行回执），对应 OTA PRD 9.7.4 operate=5：OTA 平台在
     * 创建升级任务前主动探测目标设备磁盘/网络等资源状态，走统一下行发布
     * {@code waitAck=true}，不产生 {@code DeviceUplinkEvent}。见 OTA 平台详细设计 2.1。
     */
    public static final String TOPIC_OTA_RESOURCE_PROBE = "ota/resource-probe";

    /** SLAM 建图/定位/导航（上行/下行），前缀，实际 Topic 为 {@code slam/*}。 */
    public static final String TOPIC_SLAM_PREFIX = "slam/";

    /** 数采指令与 OSS 回传（上行/下行）前缀，实际 Topic 为 {@code datacollect/*}。 */
    public static final String TOPIC_DATACOLLECT_PREFIX = "datacollect/";

    /**
     * HTTP-MQTT 桥接统一 ACK 回执 Topic 后缀（上行）。无论下行通过哪个
     * {@code topicSuffix} 发布，默认要求设备向 {@code device/{deviceCode}/bridge-ack}
     * 上报 JSON，且 JSON 内 {@code commandId} 字段必须与下行请求一一对应，
     * 供 publish-and-wait 关联。若设备族确需差异化协议，可在下行请求中显式
     * 指定 {@code ackTopicSuffix}/{@code ackCommandIdField}，并在
     * {@code comm_hub_topic_route} 为对应 ACK Topic 后缀配置 {@code BRIDGE_ACK}。
     * 自定义 ACK Topic 应采用版本化后缀（如 {@code bridge-ack/v2}），避免
     * 与默认契约语义漂移。见设备通信中台详细设计 4.3.1。
     */
    public static final String TOPIC_BRIDGE_ACK = "bridge-ack";

    /** 拼出完整 Topic：{@code device/{deviceCode}/{topicSuffix}}。 */
    public static String fullTopic(String deviceCode, String topicSuffix) {
        return "device/" + deviceCode + "/" + topicSuffix;
    }
}
