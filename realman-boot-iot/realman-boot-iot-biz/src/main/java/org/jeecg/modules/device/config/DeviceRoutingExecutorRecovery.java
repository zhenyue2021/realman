package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * MQTT 路由线程池（deviceTaskExecutor）恢复工具。
 *
 * <p>路由池饱和时清空积压比重建 MQTT 连接更有效：饱和根因多为队列 backlog 或 worker 慢，
 * 重建连接无法 drain 已有 2000+ 任务。
 */
@Slf4j
@Component
public class DeviceRoutingExecutorRecovery {

    private final ThreadPoolTaskExecutor routingExecutor;

    public DeviceRoutingExecutorRecovery(@Qualifier("deviceTaskExecutor") Executor routingExecutorBean) {
        if (!(routingExecutorBean instanceof ThreadPoolTaskExecutor taskExecutor)) {
            throw new IllegalStateException("deviceTaskExecutor must be ThreadPoolTaskExecutor");
        }
        this.routingExecutor = taskExecutor;
    }

    public RoutingPoolSnapshot snapshot() {
        ThreadPoolExecutor pool = routingExecutor.getThreadPoolExecutor();
        return new RoutingPoolSnapshot(
                pool.getQueue().size(),
                routingExecutor.getActiveCount(),
                routingExecutor.getMaxPoolSize(),
                pool.getCompletedTaskCount());
    }

    /**
     * 清空路由池等待队列，释放积压。正在执行的 worker 任务不中断。
     *
     * @return 被丢弃的排队任务数
     */
    public int purgeBacklog() {
        ThreadPoolExecutor pool = routingExecutor.getThreadPoolExecutor();
        int cleared = pool.getQueue().size();
        if (cleared > 0) {
            pool.getQueue().clear();
            log.warn("[RoutingPool] 已清空路由池积压 queueCleared={} (active={}, completed={})",
                    cleared, pool.getActiveCount(), pool.getCompletedTaskCount());
        }
        return cleared;
    }

    public record RoutingPoolSnapshot(int queueSize, int activeCount, int maxPoolSize, long completedCount) {

        public boolean isSaturated(int queueWatermark) {
            return queueSize >= queueWatermark && activeCount >= maxPoolSize;
        }
    }
}
