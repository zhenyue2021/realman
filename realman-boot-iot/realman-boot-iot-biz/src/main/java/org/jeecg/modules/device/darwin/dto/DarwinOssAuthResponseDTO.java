package org.jeecg.modules.device.darwin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DarwinOssAuthResponseDTO {

    private String traceId;
    /** 原样返回，达尔文用于匹配请求 */
    private String correlationId;
    private boolean success;
    /** 本平台 HTTP 上传接口地址 */
    private String uploadUrl;
    /** 一次性 Token，TTL 1 小时 */
    private String uploadToken;
    private LocalDateTime tokenExpireAt;
    private String errorCode;
    private String errorMsg;
}
