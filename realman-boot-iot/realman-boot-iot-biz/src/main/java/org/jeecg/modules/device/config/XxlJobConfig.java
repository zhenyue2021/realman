package org.jeecg.modules.device.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses:http://localhost:8080/xxl-job-admin}") private String adminAddresses;
    @Value("${xxl.job.executor.appname:device-executor}")                    private String appname;
    @Value("${xxl.job.executor.port:9999}")                                  private int    port;
    @Value("${xxl.job.accessToken:xxl-job-default-token}")                   private String accessToken;

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
