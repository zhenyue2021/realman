package org.jeecg.modules.device.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.toolkit.JdbcUtils;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
    /** MQTT 路由池见 {@link DeviceRoutingExecutor}（deviceTaskExecutor） */

    /**
     * DB/IO 异步写池：专用于 persistAsync、recordLog 等持久化操作（指令记录 recordSend 已改为同步），
     * 与 MQTT 路由池（deviceTaskExecutor）隔离，防止 DB 慢查询阻塞消息吞吐。
     */
    @Bean("devicePersistExecutor")
    public Executor devicePersistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("device-persist-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            if (!pool.isShutdown()) {
                log.warn("[devicePersistExecutor] 队列已满，丢弃持久化任务 (active={}, queue={})",
                        pool.getActiveCount(), pool.getQueue().size());
            }
        });
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

    /**
     * MQTT 下行发布池：ExtParams 等需在路由线程外完成 publish，避免 Paho 同步发布占满 deviceTaskExecutor。
     */
    @Bean("mqttPublishExecutor")
    public Executor mqttPublishExecutor() {
        return buildAuxExecutor("mqtt-publish-", 4, 8, 500);
    }

    /**
     * 轻量通知池：slave/master 状态 WebSocket 推送等，与路由池隔离。
     */
    @Bean("deviceNotifyExecutor")
    public Executor deviceNotifyExecutor() {
        return buildAuxExecutor("device-notify-", 4, 8, 1000);
    }

    /**
     * Darwin HTTP 直连池：{@code darwin.integration.http-enabled=true} 时，OSS 授权/文件地址/
     * 设备状态改走同步 HTTP 调用外部数采平台，不能占用 MQTT 消息处理线程，见 {@code DarwinHttpClient}。
     */
    @Bean("darwinHttpExecutor")
    public Executor darwinHttpExecutor() {
        return buildAuxExecutor("darwin-http-", 4, 16, 500);
    }

    private static Executor buildAuxExecutor(String threadPrefix, int core, int max, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            if (!pool.isShutdown()) {
                log.warn("[{}] 队列已满，丢弃任务 (active={}, queue={})",
                        threadPrefix, pool.getActiveCount(), pool.getQueue().size());
            }
        });
        executor.setTaskDecorator(runnable -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
