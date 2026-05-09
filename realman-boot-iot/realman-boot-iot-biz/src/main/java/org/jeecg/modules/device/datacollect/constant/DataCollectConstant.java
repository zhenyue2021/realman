package org.jeecg.modules.device.datacollect.constant;

public final class DataCollectConstant {

    private DataCollectConstant() {}

    // ===== RocketMQ Topics（待与数采平台对齐后替换实际 Topic 名）=====
    /** Teleop → Darwin：转发机器人 OSS 授权请求 */
    public static final String MQ_TOPIC_OSS_AUTH_REQUEST  = "TELEP_ROBOT_OSS_AUTH_REQUEST";
    /** Darwin → Teleop：返回 STS 临时凭证 */
    public static final String MQ_TOPIC_OSS_AUTH_RESPONSE = "TELEP_ROBOT_OSS_AUTH_RESPONSE";
    /** Teleop → Darwin：转发机器人 OSS 地址上报 */
    public static final String MQ_TOPIC_FILE_REPORT       = "TELEP_ROBOT_FILE_REPORT";

    // ===== RocketMQ Consumer Groups =====
    public static final String MQ_GROUP_OSS_AUTH_RESPONSE = "TELEP_ROBOT_OSS_AUTH_RESPONSE_GROUP";

    // ===== RocketMQ Tags =====
    public static final String MQ_TAG_REQUEST  = "REQUEST";
    public static final String MQ_TAG_RESPONSE = "RESPONSE";
    public static final String MQ_TAG_REPORT   = "REPORT";

    // ===== MQTT 上行 Topic 路径（机器人 → 遥操平台）=====
    /** 机器人请求 OSS 上传授权，完整 Topic：device/{code}/datacollect/collectUrlRequest */
    public static final String MQTT_UP_COLLECT_URL_REQUEST = "datacollect/collectUrlRequest";
    /** 机器人 OSS 上传完成后回传地址，完整 Topic：device/{code}/datacollect/ossAdressReport */
    public static final String MQTT_UP_OSS_ADDRESS_REPORT  = "datacollect/ossAdressReport";

    // ===== MQTT 下行 Topic 路径（遥操平台 → 机器人）=====
    /** 开始采集指令，完整 Topic：device/{code}/datacollect/startCollect */
    public static final String MQTT_DOWN_START_COLLECT    = "datacollect/startCollect";
    /** 停止采集指令，完整 Topic：device/{code}/datacollect/stopCollect */
    public static final String MQTT_DOWN_STOP_COLLECT     = "datacollect/stopCollect";
    /** OSS STS 凭证下发，完整 Topic：device/{code}/datacollect/collectUrlResponse */
    public static final String MQTT_DOWN_COLLECT_URL_RESP = "datacollect/collectUrlResponse";

    // ===== 设备状态推送（Teleop → Darwin）=====
    public static final String MQ_TOPIC_DEVICE_STATUS    = "DARWIN_DEVICE_STATUS";
    public static final String MQ_TAG_ONLINE             = "ONLINE";
    public static final String MQ_TAG_OFFLINE            = "OFFLINE";

    // ===== 工单创建（Darwin → Teleop）=====
    public static final String MQ_TOPIC_WORK_ORDER_IN    = "DARWIN_WORKORDER_IN";
    public static final String MQ_TAG_CREATE             = "CREATE";
    public static final String MQ_GROUP_WORK_ORDER_IN    = "DARWIN_WORKORDER_CONSUMER_GROUP";

    // ===== Darwin 文件上报（Darwin → Teleop MinIO）=====
    public static final String MQ_TOPIC_FILE_REPORT_IN   = "DARWIN_FILE_REPORT";
    public static final String MQ_TAG_UPLOAD             = "UPLOAD";
    public static final String MQ_GROUP_FILE_REPORT_IN   = "DARWIN_FILE_REPORT_CONSUMER_GROUP";

    // ===== Redis Keys =====
    /** requestId → deviceCode 映射，TTL 2 小时；Key = prefix + requestId */
    public static final String REDIS_OSS_REQUEST_PREFIX  = "datacollect:oss:req:";
    /** OSS 地址上报去重，TTL 24 小时；Key = prefix + deviceCode + ":" + ossAddress */
    public static final String REDIS_REPORT_DEDUP_PREFIX = "datacollect:report:dedup:";
    /** Darwin 文件上报去重，TTL 24 小时；Key = prefix + darwinFileId */
    public static final String REDIS_DARWIN_FILE_DEDUP   = "darwin:file:report:";
}
