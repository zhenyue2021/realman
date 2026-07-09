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

    /** SLAM 建图/定位/导航（上行/下行），前缀，实际 Topic 为 {@code slam/*}。 */
    public static final String TOPIC_SLAM_PREFIX = "slam/";

    /** 数采指令与 OSS 回传（上行/下行）前缀，实际 Topic 为 {@code datacollect/*}。 */
    public static final String TOPIC_DATACOLLECT_PREFIX = "datacollect/";

    /** 拼出完整 Topic：{@code device/{deviceCode}/{topicSuffix}}。 */
    public static String fullTopic(String deviceCode, String topicSuffix) {
        return "device/" + deviceCode + "/" + topicSuffix;
    }
}
