package org.jeecg.modules.device.datacollect.dto.mqtt;

import lombok.Builder;
import lombok.Data;

/** 遥操平台 → 机器人：OSS STS 临时凭证下发（设计文档 5.1.4.3） */
@Data
@Builder
public class CollectUrlResponseCmd {

    private String requestId;
    private Long timestamp;
    private String deviceSn;
    private StsParams params;

    @Data
    @Builder
    public static class StsParams {
        private String endpoint;
        private String bucket;
        private String bjExpiration;
        private String utcExpiration;
        private String accessKeyId;
        /** 敏感字段，禁止打印日志 */
        private String accessKeySecret;
        /** 敏感字段，禁止打印日志 */
        private String securityToken;
    }
}
