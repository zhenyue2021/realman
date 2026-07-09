package org.jeecg.modules.devicemgmt.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 旧 {@code realman_iot} 库的只读第二数据源，仅在
 * {@code realman.migration.legacy-iot.enabled=true} 时装配，见
 * {@link LegacyIotDataSourceProperties}。与 {@code IDeviceMigrationService} 的存量设备
 * 迁移工具配套，不参与应用主数据源体系（不注册为 {@code @Primary}，不接入
 * MyBatis-Plus），迁移窗口结束后可整体关闭。
 */
@Configuration
@EnableConfigurationProperties(LegacyIotDataSourceProperties.class)
@ConditionalOnProperty(prefix = "realman.migration.legacy-iot", name = "enabled", havingValue = "true")
public class LegacyIotDataSourceConfig {

    @Bean(name = "legacyIotDataSource")
    public DataSource legacyIotDataSource(LegacyIotDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setPoolName("legacyIotMigrationPool");
        dataSource.setMaximumPoolSize(5);
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean(name = "legacyIotJdbcTemplate")
    public JdbcTemplate legacyIotJdbcTemplate(@Qualifier("legacyIotDataSource") DataSource legacyIotDataSource) {
        return new JdbcTemplate(legacyIotDataSource);
    }
}
