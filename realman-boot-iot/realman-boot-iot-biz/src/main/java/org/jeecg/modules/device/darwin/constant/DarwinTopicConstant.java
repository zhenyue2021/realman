package org.jeecg.modules.device.darwin.constant;

public final class DarwinTopicConstant {

    private DarwinTopicConstant() {}

    // Topics
    public static final String DEVICE_STATUS       = "DARWIN_DEVICE_STATUS";
    public static final String WORK_ORDER_IN       = "DARWIN_WORKORDER_IN";
    public static final String OSS_AUTH_REQUEST    = "DARWIN_OSS_AUTH_REQUEST";
    public static final String OSS_AUTH_RESPONSE   = "DARWIN_OSS_AUTH_RESPONSE";
    public static final String FILE_REPORT         = "DARWIN_FILE_REPORT";

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
