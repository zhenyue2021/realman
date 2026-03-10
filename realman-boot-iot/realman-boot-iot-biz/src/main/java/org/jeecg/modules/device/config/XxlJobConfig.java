package org.jeecg.modules.device.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 执行器配置
 *
 * <p>注册本服务为 XXL-Job 执行器，使 {@link org.jeecg.modules.device.scheduler.DeviceSchedulerJob}
 * 中的 {@code @XxlJob} 定时任务能被 XXL-Job Admin 调度。
 *
 * <p>若 xxl.job.enabled=false 或 Admin 不可达，执行器注册会失败（打印 WARN 日志），
 * 但不影响服务启动，定时任务仅无法被 XXL-Job 触发。
 *
 * <p>注册的 JobHandler：
 * <ul>
 *   <li>{@code deviceOfflineCheckJob}  - 设备离线检测（建议 Cron: 0 * * * * ?）</li>
 *   <li>{@code otaUpgradeTimeoutCheckJob} - OTA 超时检测（建议 Cron: 0 0/5 * * * ?）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class XxlJobConfig {

    /** XXL-Job Admin 地址（多个用逗号分隔，支持集群部署） */
    @Value("${xxl.job.admin.addresses:http://localhost:8080/xxl-job-admin}")
    private String adminAddresses;

    /** 执行器名称（需与 XXL-Job Admin 后台注册的 AppName 一致） */
    @Value("${xxl.job.executor.appname:device-executor}")
    private String appname;

    /** 执行器监听端口（用于 Admin 回调，需确保网络可达） */
    @Value("${xxl.job.executor.port:9999}")
    private int port;

    /** 通信令牌（Admin 和执行器必须配置相同的 accessToken） */
    @Value("${xxl.job.accessToken:xxl-job-default-token}")
    private String accessToken;

    /**
     * 创建 XXL-Job 执行器实例并注册到 Admin
     *
     * @return 已配置的 XXL-Job Spring 执行器
     */
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info("[XXL-Job] 初始化执行器, adminAddr={}", adminAddresses);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        return executor;
    }
}
