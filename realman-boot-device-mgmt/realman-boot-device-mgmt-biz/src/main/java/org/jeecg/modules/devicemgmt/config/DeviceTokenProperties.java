package org.jeecg.modules.devicemgmt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 设备业务身份 Token（JWT）配置。对齐 OTA PRD 9.7.5：
 * Token 有效期默认 365 天，最小 30 天，最大 3650 天（本类不做范围校验，
 * 范围校验属于后续"系统设置"REST 的职责）。
 *
 * <pre>
 * device:
 *   token:
 *     secret: ${DEVICE_TOKEN_SECRET:change-me-in-production}
 *     issuer: realman-device-mgmt
 *     expiry-days: 365
 * </pre>
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "device.token")
public class DeviceTokenProperties {

    /** HMAC256 签名密钥，生产环境必须通过 Nacos/环境变量覆盖，不可使用默认值 */
    private String secret = "change-me-in-production";

    private String issuer = "realman-device-mgmt";

    /** Token 有效期（天），默认 365 天 */
    private int expiryDays = 365;
}
