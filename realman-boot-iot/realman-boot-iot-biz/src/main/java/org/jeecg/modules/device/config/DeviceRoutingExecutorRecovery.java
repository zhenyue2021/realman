package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private final DeviceRoutingExecutor routingExecutor;

    public DeviceRoutingExecutorRecovery(DeviceRoutingExecutor routingExecutor) {
        this.routingExecutor = routingExecutor;
    }

    public RoutingPoolSnapshot snapshot() {
        ThreadPoolExecutor pool = routingExecutor.getDelegate().getThreadPoolExecutor();
        return new RoutingPoolSnapshot(
                pool.getQueue().size(),
                routingExecutor.getDelegate().getActiveCount(),
                routingExecutor.getDelegate().getMaxPoolSize(),
                pool.getCompletedTaskCount());
    }

    /**
     * 清空路由池等待队列，释放积压。正在执行的 worker 任务不中断。
     *
     * @return 被丢弃的排队任务数
     */
    public int purgeBacklog() {
        ThreadPoolExecutor pool = routingExecutor.getDelegate().getThreadPoolExecutor();
        int cleared = pool.getQueue().size();
        if (cleared > 0) {
            pool.getQueue().clear();
            log.warn("[RoutingPool] 已清空路由池积压 queueCleared={} (active={}, completed={})",
                    cleared, pool.getActiveCount(), pool.getCompletedTaskCount());
        }
        return cleared;
    }

    /**
     * 中断所有名为 "device-task-*" 的存活线程。
     *
     * <p>配合 Redis timeout，阻塞在 Lettuce socket read 的线程会收到中断/超时异常，
     * 再由 handler try-catch 吸收后重新拾取队列任务。
     *
     * @return 被中断的线程数
     */
    public int interruptZombieWorkers() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("device-task-") && t.isAlive()) {
                t.interrupt();
                count++;
            }
        }
        if (count > 0) {
            log.warn("[RoutingPool] 已中断僵死 worker 线程 count={}", count);
        }
        return count;
    }

    /**
     * shutdownNow 当前池并创建全新 ThreadPoolTaskExecutor。
     *
     * @return shutdownNow 丢弃的未执行任务数
     */
    public int recreatePool() {
        return routingExecutor.recreatePool();
    }

    public record RoutingPoolSnapshot(int queueSize, int activeCount, int maxPoolSize, long completedCount) {

        public boolean isSaturated(int queueWatermark) {
            return queueSize >= queueWatermark && activeCount >= maxPoolSize;
        }
    }
}
