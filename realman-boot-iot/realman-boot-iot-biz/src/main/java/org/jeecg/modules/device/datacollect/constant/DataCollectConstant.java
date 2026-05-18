package org.jeecg.modules.device.datacollect.constant;

public final class DataCollectConstant {

    private DataCollectConstant() {}

    // =========================================================================
    // 链路一：采集授权（OSS STS 凭证申请）
    //
    // ① 机器人   →  遥操平台  MQTT  MQTT_UP_COLLECT_URL_REQUEST
    //      Handler : CollectUrlRequestHandler
    // ② 遥操平台 →  数采平台  MQ    MQ_TOPIC_OSS_AUTH_REQUEST : MQ_TAG_REQUEST
    //      Producer: OssAuthRequestProducer
    //      Redis   : REDIS_OSS_REQUEST_PREFIX + requestId → deviceCode（TTL 2h）
    // ③ 数采平台 →  遥操平台  MQ    MQ_TOPIC_OSS_AUTH_RESPONSE : MQ_TAG_RESPONSE
    //      Consumer: OssAuthResponseConsumer（Group: MQ_GROUP_OSS_AUTH_RESPONSE）
    // ④ 遥操平台 →  机器人    MQTT  MQTT_DOWN_COLLECT_URL_RESP
    //      Service : DataCollectCommandService#sendCollectUrlResponse
    //      DTO     : CollectUrlResponseCmd（code=0 成功；code=1 失败，params=null）
    // =========================================================================

    // ===== 链路一 MQTT Topics =====
    /** ① 机器人 → 遥操平台：请求 OSS 上传授权，完整 Topic：device/{code}/datacollect/collectUrlRequest */
    public static final String MQTT_UP_COLLECT_URL_REQUEST = "datacollect/collectUrlRequest";
    /** 机器人 → 遥操平台：设备上线信息上报，完整 Topic：device/{code}/datacollect/deviceOnline */
    public static final String MQTT_UP_DEVICE_ONLINE       = "datacollect/deviceOnline";
    /** ④ 遥操平台 → 机器人：下发 OSS STS 凭证，完整 Topic：device/{code}/datacollect/collectUrlResponse */
    public static final String MQTT_DOWN_COLLECT_URL_RESP  = "datacollect/collectUrlResponse";

    // ===== 链路一 RocketMQ Topics =====
    /** ② 遥操平台 → 数采平台：转发机器人 OSS 授权请求 */
    public static final String MQ_TOPIC_OSS_AUTH_REQUEST  = "daily_GLN_PLATFORM";
    /** ③ 数采平台 → 遥操平台：返回 STS 临时凭证 */
    public static final String MQ_TOPIC_OSS_AUTH_RESPONSE = "daily_GLN_PLATFORM";

    // ===== 链路一 RocketMQ Consumer Groups =====
    public static final String MQ_GROUP_OSS_AUTH_RESPONSE = "CID_GLN_PLATFORM_AUTH_RESULT";

    // ===== 链路一 RocketMQ Tags =====
    public static final String MQ_TAG_REQUEST  = "OSS_AUTH_REQUEST";
    public static final String MQ_TAG_RESPONSE = "OSS_AUTH_RESPONSE";

    // ===== 链路一 Redis Keys =====
    /** requestId → deviceCode 映射，TTL 2 小时；Key = prefix + requestId */
    public static final String REDIS_OSS_REQUEST_PREFIX  = "datacollect:oss:req:";

    // =========================================================================
    // 链路二：OSS 文件地址上报
    //
    // ① 机器人   →  遥操平台  MQTT  MQTT_UP_OSS_ADDRESS_REPORT
    //      Handler : OssAddressReportHandler
    //      Redis   : REDIS_REPORT_DEDUP_PREFIX + deviceCode + ":" + ossAddress（TTL 24h，防重发）
    // ② 遥操平台 →  数采平台  MQ    MQ_TOPIC_FILE_REPORT : MQ_TAG_REPORT
    //      Producer: FileAddressReportProducer
    // =========================================================================

    // ===== 链路二 MQTT Topics =====
    /** ① 机器人 → 遥操平台：OSS 上传完成后回传文件地址，完整 Topic：device/{code}/datacollect/ossAdressReport */
    public static final String MQTT_UP_OSS_ADDRESS_REPORT  = "datacollect/ossAdressReport";

    // ===== 链路二 RocketMQ Topics =====
    /** ② 遥操平台 → 数采平台：转发机器人 OSS 文件地址上报 */
    public static final String MQ_TOPIC_FILE_REPORT        = "daily_GLN_PLATFORM";

    // ===== 链路二 RocketMQ Tags =====
    public static final String MQ_TAG_REPORT   = "UPLOAD_COLLECTION_RESULT";

    // ===== 链路二 Redis Keys =====
    /** OSS 地址上报去重，TTL 24 小时；Key = prefix + deviceCode + ":" + ossAddress */
    public static final String REDIS_REPORT_DEDUP_PREFIX = "datacollect:report:dedup:";

    // =========================================================================
    // 其他独立链路
    // =========================================================================

    // ===== 设备状态推送（Teleop → Darwin）=====
    public static final String MQ_TOPIC_DEVICE_STATUS    = "daily_GLN_PLATFORM";
    /** 上线/离线事件共用同一个 MQ Tag，用于 broker 侧过滤 */
    public static final String MQ_TAG_DEVICE_STATUS      = "DEVICE_STATUS";
    /** @deprecated 使用 MQ_TAG_DEVICE_STATUS */
    public static final String MQ_TAG_ONLINE             = MQ_TAG_DEVICE_STATUS;
    /** @deprecated 使用 MQ_TAG_DEVICE_STATUS */
    public static final String MQ_TAG_OFFLINE            = MQ_TAG_DEVICE_STATUS;
    /** 消息体 eventType 字段值：设备上线 */
    public static final String MQ_EVENT_ONLINE           = "ONLINE";
    /** 消息体 eventType 字段值：设备离线 */
    public static final String MQ_EVENT_OFFLINE          = "OFFLINE";

    // ===== 工单创建（Darwin → Teleop）=====
    public static final String MQ_TOPIC_WORK_ORDER_IN    = "daily_GLN_PLATFORM";
    public static final String MQ_TAG_CREATE             = "GET_WORK_ORDER_RESPONSE";
    public static final String MQ_GROUP_WORK_ORDER_IN    = "CID_GLN_PLATFORM_CREATE_ORDER";

    // ===== Darwin 文件上报（Darwin → Teleop MinIO）=====
    public static final String MQ_TOPIC_FILE_REPORT_IN   = "daily_GLN_PLATFORM";
    public static final String MQ_TAG_UPLOAD             = "UPLOAD";
    public static final String MQ_GROUP_FILE_REPORT_IN   = "CID_GLN_UPLOAD_COLLECTION_RESULT";

    // ===== Darwin 文件上报去重 Redis Key =====
    /** Darwin 文件上报去重，TTL 24 小时；Key = prefix + darwinFileId */
    public static final String REDIS_DARWIN_FILE_DEDUP   = "darwin:file:report:";

    // ===== 采集控制指令（遥操平台 → 机器人，由工单触发）=====
    /** 开始采集指令，完整 Topic：device/{code}/datacollect/startCollect */
    public static final String MQTT_DOWN_START_COLLECT    = "datacollect/startCollect";
    /** 停止采集指令，完整 Topic：device/{code}/datacollect/stopCollect */
    public static final String MQTT_DOWN_STOP_COLLECT     = "datacollect/stopCollect";
}
