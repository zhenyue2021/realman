package org.jeecg.modules.device.darwin.constant;

public final class DarwinTopicConstant {

    private DarwinTopicConstant() {}

    // Topics — Darwin 文件上传到遥操平台 MinIO（Darwin 主动上传）
    public static final String DEVICE_STATUS       = "DARWIN_DEVICE_STATUS";
    public static final String WORK_ORDER_IN       = "DARWIN_WORKORDER_IN";
    public static final String OSS_AUTH_REQUEST    = "DARWIN_OSS_AUTH_REQUEST";   // Darwin→Teleop：Darwin 请求上传 Token
    public static final String OSS_AUTH_RESPONSE   = "DARWIN_OSS_AUTH_RESPONSE";  // Teleop→Darwin：返回 HTTP 上传 URL
    public static final String FILE_REPORT         = "DARWIN_FILE_REPORT";

    // Topics — 机器人远程数采 OSS 授权（机器人直传 Aliyun OSS）
    public static final String ROBOT_OSS_AUTH_REQUEST  = "DARWIN_ROBOT_OSS_AUTH_REQUEST";  // Teleop→Darwin：转发机器人授权请求
    public static final String ROBOT_OSS_AUTH_RESPONSE = "DARWIN_ROBOT_OSS_AUTH_RESPONSE"; // Darwin→Teleop：返回 STS 凭证

    // Tags
    public static final String TAG_ONLINE   = "ONLINE";
    public static final String TAG_OFFLINE  = "OFFLINE";
    public static final String TAG_CREATE   = "CREATE";
    public static final String TAG_REQUEST  = "REQUEST";
    public static final String TAG_RESPONSE = "RESPONSE";
    public static final String TAG_UPLOAD   = "UPLOAD";

    // Redis key prefix
    public static final String REDIS_UPLOAD_TOKEN  = "darwin:upload:token:";
    public static final String REDIS_FILE_DEDUP    = "darwin:file:report:";
}
