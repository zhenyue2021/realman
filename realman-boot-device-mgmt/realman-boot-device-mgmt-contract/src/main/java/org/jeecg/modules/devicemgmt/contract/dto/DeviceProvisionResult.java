package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 注册结果。{@code deviceSecret} 用于后续 MQTT CONNECT；{@code deviceToken} 是业务身份
 * Token（JWT），随业务报文携带（见设备基座详细设计 3.3 双凭证体系）。
 */
@Data
@Schema(description = "设备注册结果")
public class DeviceProvisionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "内部唯一标识（UUID）")
    private String deviceId;

    @Schema(description = "MQTT 连接密码，仅在注册/密钥重置时返回一次")
    private String deviceSecret;

    @Schema(description = "业务身份 Token（JWT）")
    private String deviceToken;

    @Schema(description = "deviceToken 过期时间")
    private LocalDateTime tokenExpiresAt;

    @Schema(description = "是否为重注册（Token 吊销后重新接入），用于审计标注 isReRegistration")
    private boolean reRegistration;
}
