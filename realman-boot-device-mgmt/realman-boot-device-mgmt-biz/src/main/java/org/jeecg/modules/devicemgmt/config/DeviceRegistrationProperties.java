package org.jeecg.modules.devicemgmt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 一次性注册凭证配置。对齐 OTA PRD 9.8.5：默认有效期 365 天。
 *
 * <pre>
 * device:
 *   registration:
 *     secret-expiry-days: 365
 * </pre>
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "device.registration")
public class DeviceRegistrationProperties {

    private int secretExpiryDays = 365;
}
