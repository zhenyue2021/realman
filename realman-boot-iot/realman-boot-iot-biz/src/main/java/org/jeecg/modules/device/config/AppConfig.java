package org.jeecg.modules.device.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.toolkit.JdbcUtils;
import io.minio.MinioClient;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AppConfig {

    @Value("${minio.endpoint:http://localhost:9000}")   private String endpoint;
    @Value("${minio.access-key:minioadmin}")            private String accessKey;
    @Value("${minio.secret-key:minioadmin}")            private String secretKey;

    @Autowired
    private DataSource dataSource;

    /** IoT 独立运行时使用：替代平台 MybatisPlusSaasConfig（单数据源，无 dynamic-datasource） */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        DbType dbType = null;
        try {
            dbType = JdbcUtils.getDbType(dataSource.getConnection().getMetaData().getURL());
        } catch (SQLException ignored) {
        }
        if (dbType != null && (dbType == DbType.SQL_SERVER || dbType == DbType.SQL_SERVER2005)) {
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.SQL_SERVER2005));
        } else {
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        }
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(endpoint)
                .credentials(accessKey, secretKey).build();
    }

    /** 使用平台 WebMvcConfiguration 中的 @Primary ObjectMapper，无需在此重复定义 */

    @Bean("deviceTaskExecutor")
    public Executor deviceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 200台设备稳态 ~1200 msg/s，每条 ~5ms → 需要 6 线程，核心设为 20 避免冷启动扩容延迟
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        // 队列 2000：为 Redis 短暂抖动（<1.5s）提供缓冲；超出后丢弃（见下方拒绝策略）
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("device-task-");
        // 丢弃最旧任务（非阻塞）：队列满时绝不让 Paho 接收线程执行任务，
        // 否则 Paho 阻塞 → TCP 窗口耗尽 → EMQX mqueue 堆积 → 复现断连
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        // MDC 跨线程传播：提交任务时快照父线程 MDC，子线程执行前恢复，finally 清理防止污染
        executor.setTaskDecorator(runnable -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (mdc != null) MDC.setContextMap(mdc);
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }

    /** WebSocket：使用平台 WebSocketConfig 中的 serverEndpointExporter，不在此重复定义 */
}
