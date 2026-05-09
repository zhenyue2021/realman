package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Data;

/** Darwin → Teleop（RocketMQ）：数采平台返回 STS 临时凭证 */
@Data
public class OssAuthResponseMsg {
    private String traceId;
    private String requestId;
    private boolean success;
    private String endpoint;
    private String bucket;
    private String bjExpiration;
    private String utcExpiration;
    private String accessKeyId;
    /** 敏感字段，禁止打印日志 */
    private String accessKeySecret;
    /** 敏感字段，禁止打印日志 */
    private String securityToken;
    private String errorCode;
    private String errorMsg;
}
