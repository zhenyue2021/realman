package org.jeecg.modules.device.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.config.DeviceRoutingExecutorRecovery;
import org.jeecg.modules.device.config.MqttConfig;
import org.jeecg.modules.device.config.DeviceRoutingExecutorRecovery.RoutingPoolSnapshot;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MQTT 客户端存活监控（Watchdog）
 *
 * <p>检测 1 — 消息空闲超时：{@link MqttMessageDispatcher#lastReceivedTs} 超时 → 重建 MQTT（Paho 僵死）。
 * <p>检测 2 — 路由池饱和：清空 deviceTaskExecutor 积压；若仍饱和且任务完成数停滞 → 才重建 MQTT。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttClientWatchdog {

    static final String HEARTBEAT_TOPIC = "iot-platform/heartbeat";

    private static final int SATURATED_ACTION_THRESHOLD = 2;
    private static final int QUEUE_SATURATED_SIZE = 1600;
    /** 两次检测间 completed 无增长且仍饱和，视为 worker 卡死 */
    private static final int STALL_RESTART_THRESHOLD = 2;
    private static final long RESTART_DEBOUNCE_MS = 30_000L;

    @Autowired private MqttConfig mqttConfig;
    @Autowired private MqttPublisher mqttPublisher;
    @Autowired private DeviceRoutingExecutorRecovery routingPoolRecovery;

    private int consecutiveSaturated = 0;
    private int completedStallChecks = 0;
    private long lastCompletedSnapshot = -1L;
    private final AtomicLong lastRestartScheduledMs = new AtomicLong(0);

    @Value("${mqtt.watchdog.idle-timeout-seconds:90}")
    private long idleTimeoutSeconds;

    @PostConstruct
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mqtt-watchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::publishHeartbeat, 15, 45, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::check, 15, 30, TimeUnit.SECONDS);
        log.info("[MqttWatchdog] 已启动，心跳间隔 45s，检测间隔 30s，空闲超时 {}s", idleTimeoutSeconds);
    }

    private void publishHeartbeat() {
        try {
            mqttPublisher.publishRaw(HEARTBEAT_TOPIC,
                    "{\"ts\":" + System.currentTimeMillis() + "}", 0, false);
        } catch (Exception e) {
            log.warn("[MqttWatchdog] 心跳发布失败", e);
        }
    }

    private void check() {
        try {
            checkIdleTimeout();
            checkRoutingPoolSaturation();
        } catch (Exception e) {
            log.error("[MqttWatchdog] 检测异常", e);
        }
    }

    private void checkIdleTimeout() {
        long idleMs = System.currentTimeMillis() - MqttMessageDispatcher.lastReceivedTs.get();
        if (idleMs < idleTimeoutSeconds * 1000L) {
            return;
        }
        if (!mqttConfig.isClientConnected()) {
            log.warn("[MqttWatchdog] {}s 无消息且客户端已断连，重建连接", idleMs / 1000);
        } else {
            log.warn("[MqttWatchdog] {}s 无消息但客户端仍显示已连接，疑似僵死，重建连接", idleMs / 1000);
        }
        triggerRestart("idle-timeout");
    }

    /**
     * 路由池饱和：优先清空积压（重建 MQTT 无法 drain 已有 queue）。
     * 连续饱和且 completed 不增长时，才额外重建 MQTT。
     */
    private void checkRoutingPoolSaturation() {
        RoutingPoolSnapshot snap = routingPoolRecovery.snapshot();
        boolean saturated = snap.isSaturated(QUEUE_SATURATED_SIZE);

        if (!saturated) {
            if (consecutiveSaturated > 0) {
                log.info("[MqttWatchdog] 路由池恢复正常 (queue={}, active={}/{})",
                        snap.queueSize(), snap.activeCount(), snap.maxPoolSize());
            }
            consecutiveSaturated = 0;
            completedStallChecks = 0;
            lastCompletedSnapshot = snap.completedCount();
            return;
        }

        consecutiveSaturated++;
        log.warn("[MqttWatchdog] 路由池饱和第 {} 次 (queue={}, active={}/{})",
                consecutiveSaturated, snap.queueSize(), snap.activeCount(), snap.maxPoolSize());

        if (consecutiveSaturated >= SATURATED_ACTION_THRESHOLD) {
            int cleared = routingPoolRecovery.purgeBacklog();
            log.warn("[MqttWatchdog] 路由池连续 {} 次饱和，已清空积压 {} 条",
                    consecutiveSaturated, cleared);
            consecutiveSaturated = 0;
        }

        if (lastCompletedSnapshot >= 0 && snap.completedCount() == lastCompletedSnapshot) {
            completedStallChecks++;
        } else {
            completedStallChecks = 0;
        }
        lastCompletedSnapshot = snap.completedCount();

        if (completedStallChecks >= STALL_RESTART_THRESHOLD) {
            log.warn("[MqttWatchdog] 路由池饱和且 completed 停滞 {} 次，重建 MQTT 连接", completedStallChecks);
            completedStallChecks = 0;
            triggerRestart("routing-pool-stall");
        }
    }

    private void triggerRestart(String reason) {
        long now = System.currentTimeMillis();
        long last = lastRestartScheduledMs.get();
        if (now - last < RESTART_DEBOUNCE_MS) {
            log.info("[MqttWatchdog] 跳过重建（{}），距上次不足 {}s", reason, RESTART_DEBOUNCE_MS / 1000);
            return;
        }
        if (!lastRestartScheduledMs.compareAndSet(last, now)) {
            return;
        }
        Thread restartThread = new Thread(() -> {
            try {
                log.warn("[MqttWatchdog] 触发重建连接 reason={}", reason);
                routingPoolRecovery.purgeBacklog();
                mqttConfig.restartConnection();
            } catch (Exception e) {
                log.error("[MqttWatchdog] 重建连接异常 reason={}", reason, e);
            }
        }, "mqtt-watchdog-restart");
        restartThread.setDaemon(true);
        restartThread.start();
    }
}
