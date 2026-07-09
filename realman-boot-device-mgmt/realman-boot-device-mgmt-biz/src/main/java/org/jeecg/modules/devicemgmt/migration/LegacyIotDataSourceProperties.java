package org.jeecg.modules.devicemgmt.migration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 旧 {@code realman-boot-iot} 单体设备库的只读连接配置，仅供一次性存量设备迁移使用。
 * 默认不启用（{@code enabled=false}），避免在正常运行时无谓地建立到旧库的连接；
 * 迁移窗口结束后应将其重新关闭。
 */
@Data
@ConfigurationProperties(prefix = "realman.migration.legacy-iot")
public class LegacyIotDataSourceProperties {

    private boolean enabled = false;

    private String jdbcUrl;

    private String username;

    private String password;

    private String driverClassName = "com.mysql.cj.jdbc.Driver";
}
