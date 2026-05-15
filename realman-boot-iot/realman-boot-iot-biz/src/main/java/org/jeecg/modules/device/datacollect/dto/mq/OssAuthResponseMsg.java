package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Data;

/** Darwin → Teleop（RocketMQ）：数采平台返回 STS 临时凭证 */
@Data
public class OssAuthResponseMsg {

    private String tenant;
    private String deviceCode;
    private String traceId;
    private String requestId;
    private long eventTime;
    private MsgData data;

    @Data
    public static class MsgData {
        private String endpoint;
        private String bucket;
        private String bjExpiration;
        private String utcExpiration;
        private String accessKeyId;
        /** 敏感字段，禁止打印日志 */
        private String accessKeySecret;
        /** 敏感字段，禁止打印日志 */
        private String securityToken;
        /** 错误场景由 Darwin 填充，成功时为 null */
        private String errorCode;
        private String errorMsg;

        /** 新消息格式不含 success 字段，以 accessKeyId 和 endpoint 是否存在判断授权是否成功 */
        public boolean isSuccess() {
            return accessKeyId != null && !accessKeyId.isBlank() && endpoint != null && !endpoint.isBlank();
        }
    }
}
