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
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Configuration
@EnableAsync
public class AppConfig {

    @Value("${minio.endpoint:http://localhost:9000}")   private String endpoint;
    @Value("${minio.access-key:minioadmin}")            private String accessKey;
    @Value("${minio.secret-key:minioadmin}")            private String secretKey;

    /** 拒绝处理器日志节流：5s 内最多打一条 WARN，避免队列满时日志风暴 */
    private final AtomicLong lastRejectWarnTs = new AtomicLong(0);

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
        // 否则 Paho 阻塞 → TCP 窗口耗尽 → EMQX mqueue 堆积 → 复现断连。
        // keepalive 由 MqttMessageDispatcher.keepaliveExecutor 续期 Redis，此处丢弃不影响离线判定。
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            if (pool.isShutdown()) {
                return;
            }
            var queue = pool.getQueue();
            // 腾出最旧任务，再直接 offer——绝不递归调用 pool.execute()，
            // 防止在 Paho CommsCallback 线程里自旋阻塞整个消息接收链路。
            var dropped = queue.poll();
            boolean offered = queue.offer(runnable);
            long now = System.currentTimeMillis();
            if (now - lastRejectWarnTs.getAndSet(now) > 5_000) {
                log.warn(
                        "[deviceTaskExecutor] 队列已满，丢弃最旧任务 (active={}, poolSize={}, queue={}, completed={}, dropped={}, offered={})",
                        pool.getActiveCount(),
                        pool.getPoolSize(),
                        queue.size(),
                        pool.getCompletedTaskCount(),
                        dropped != null,
                        offered);
            }
        });
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

    /**
     * DB/IO 异步写池：专用于 persistAsync、recordLog、recordSend 等持久化操作，
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
}
