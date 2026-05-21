package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MQTT 路由线程池（{@code deviceTaskExecutor}）：可重建，供 Watchdog 在 worker 卡死时恢复。
 */
@Slf4j
@Component("deviceTaskExecutor")
public class DeviceRoutingExecutor implements Executor {

    private static final int CORE_POOL_SIZE = 20;
    private static final int MAX_POOL_SIZE = 50;
    private static final int QUEUE_CAPACITY = 2000;

    private final AtomicLong lastRejectWarnTs = new AtomicLong(0);
    private volatile ThreadPoolTaskExecutor delegate;

    @PostConstruct
    void init() {
        delegate = buildExecutor();
        delegate.initialize();
        log.info("[RoutingPool] 已初始化 core={} max={} queue={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    public ThreadPoolTaskExecutor getDelegate() {
        return delegate;
    }

    /**
     * 强制关闭当前池并创建新实例。适用于 completed 长期停滞、线程卡在 I/O 的场景。
     *
     * @return shutdownNow 未能开始执行的任务数
     */
    public synchronized int recreatePool() {
        ThreadPoolTaskExecutor old = delegate;
        int queueCleared = old.getThreadPoolExecutor().getQueue().size();
        old.getThreadPoolExecutor().getQueue().clear();
        List<Runnable> dropped = old.getThreadPoolExecutor().shutdownNow();

        delegate = buildExecutor();
        delegate.initialize();
        log.warn("[RoutingPool] 已重建路由线程池 queueCleared={} shutdownNowDropped={}",
                queueCleared, dropped.size());
        return dropped.size();
    }

    private ThreadPoolTaskExecutor buildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("device-task-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            if (pool.isShutdown()) {
                return;
            }
            var queue = pool.getQueue();
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
        return executor;
    }
}
