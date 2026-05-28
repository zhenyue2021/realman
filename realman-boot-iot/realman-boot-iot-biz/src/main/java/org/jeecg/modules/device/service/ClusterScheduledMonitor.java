package org.jeecg.modules.device.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 集群定时监控基类：本节点 {@link ScheduledFuture} + Redis Pub/Sub 广播停止信号。
 *
 * <p>适用于长周期轮询类任务（如导航路径监控）；单次 MQTT ACK 等待请用 {@link MqttAckPendingService}。
 */
@Slf4j
public abstract class ClusterScheduledMonitor extends ClusterRedisMessageListener {

    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> localTasks = new ConcurrentHashMap<>();

    protected ClusterScheduledMonitor(StringRedisTemplate redisTemplate,
                                      String stopChannelPrefix,
                                      String logTag,
                                      ScheduledExecutorService scheduler) {
        super(redisTemplate, stopChannelPrefix, logTag);
        this.scheduler = scheduler;
    }

    protected boolean hasLocalTask(String taskKey) {
        return localTasks.containsKey(taskKey);
    }

    protected void scheduleFixedDelay(String taskKey, Runnable task,
                                      long initialDelay, long period, TimeUnit unit) {
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                task, initialDelay, period, unit);
        localTasks.put(taskKey, future);
    }

    /**
     * 广播停止信号；Redis 不可用时降级为本节点取消。
     */
    protected void broadcastStop(String taskKey) {
        try {
            publish(taskKey, "stop");
        } catch (Exception e) {
            log.warn("[{}] Redis 发布停止信号失败，降级本地停止: taskKey={}", logTag, taskKey, e);
            cancelLocal(taskKey);
        }
    }

    protected void cancelLocal(String taskKey) {
        ScheduledFuture<?> future = localTasks.remove(taskKey);
        if (future != null) {
            future.cancel(false);
            log.info("[{}] 本节点定时任务已取消: taskKey={}", logTag, taskKey);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String taskKey = extractKeyFromChannel(message);
        cancelLocal(taskKey);
        onStopSignal(taskKey);
    }

    /** 收到集群停止广播后的扩展钩子（可选） */
    protected void onStopSignal(String taskKey) {
    }
}
