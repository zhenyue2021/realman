package org.jeecg.modules.device.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 设备 HTTP 自注册（Provision）配置。
 *
 * <pre>
 * device:
 *   provision:
 *     enabled: true
 *     timestamp-skew-seconds: 300
 *     default-tenant-id: 1000
 * </pre>
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "device.provision")
public class DeviceProvisionProperties {

    /** 是否启用设备自注册接口 */
    private boolean enabled = false;

    /** 请求时间戳允许偏差（秒），防重放 */
    private int timestampSkewSeconds = 300;

    /** 自注册设备默认租户 ID */
    private Integer defaultTenantId;
}
