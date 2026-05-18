package org.jeecg.modules.device.datacollect.dto.mqtt;

import lombok.Builder;
import lombok.Data;

/**
 * 遥操平台 → 机器人：OSS STS 临时凭证下发（设计文档 5.1.4.3）
 *
 * <p>错误响应约定：{@code code != 0} 时表示授权失败，{@code params} 为 null，
 * {@code message} 携带错误描述；成功时 {@code code = 0}，{@code message} 为 null。
 */
@Data
@Builder
public class CollectUrlResponseCmd {

    private String requestId;
    private Long timestamp;
    private String deviceSn;
    /** 0=成功，1=OSS授权失败 */
    private Integer code;
    /** 错误描述，成功时为 null */
    private String message;
    /** 成功时携带 STS 凭证，失败时为 null */
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
