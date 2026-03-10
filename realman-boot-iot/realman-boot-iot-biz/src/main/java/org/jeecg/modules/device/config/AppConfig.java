package org.jeecg.modules.device.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AppConfig {

    @Value("${minio.endpoint:http://localhost:9000}")   private String endpoint;
    @Value("${minio.access-key:minioadmin}")            private String accessKey;
    @Value("${minio.secret-key:minioadmin}")            private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(endpoint)
                .credentials(accessKey, secretKey).build();
    }

    /** 使用平台 WebMvcConfiguration 中的 @Primary ObjectMapper，无需在此重复定义 */

    @Bean("deviceTaskExecutor")
    public Executor deviceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("device-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
