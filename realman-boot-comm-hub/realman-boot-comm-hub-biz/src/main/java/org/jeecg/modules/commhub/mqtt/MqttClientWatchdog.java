package org.jeecg.modules.commhub.mqtt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.commhub.config.MqttClientProperties;
import org.jeecg.modules.commhub.config.MqttConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MQTT 连接看门狗：定时自检心跳 + 空闲超时检测。独立实现，模式与
 * {@code realman-boot-iot} 一致（不复用其代码）——{@code client.isConnected()}
 * 为 true 不代表连接真的健康，Paho 内部收发线程可能已经僵死但连接状态位未翻转，
 * 因此用"消息接收是否静默过久"作为更可靠的健康判定依据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttClientWatchdog {

    private static final long RESTART_DEBOUNCE_MS = 30_000L;

    private final MqttClientProperties properties;
    private final MqttConfig mqttConfig;

    private final AtomicLong lastRestartAt = new AtomicLong(0L);
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mqtt-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safePublishHeartbeat,
                15, properties.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::safeCheckIdleTimeout,
                15, properties.getWatchdogCheckIntervalSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void safePublishHeartbeat() {
        try {
            mqttConfig.publishHeartbeat();
        } catch (Exception e) {
            log.warn("[comm-hub] Watchdog 心跳发布异常: {}", e.getMessage());
        }
    }

    private void safeCheckIdleTimeout() {
        try {
            checkIdleTimeout();
        } catch (Exception e) {
            log.warn("[comm-hub] Watchdog 空闲检测异常: {}", e.getMessage());
        }
    }

    private void checkIdleTimeout() {
        long idleMs = System.currentTimeMillis() - MqttMessageDispatcher.LAST_RECEIVED_TS.get();
        long thresholdMs = properties.getIdleTimeoutSeconds() * 1000L;
        if (idleMs > thresholdMs) {
            triggerRestart("空闲超时 idleMs=" + idleMs + " thresholdMs=" + thresholdMs);
        }
    }

    private void triggerRestart(String reason) {
        long now = System.currentTimeMillis();
        long previous = lastRestartAt.get();
        if (now - previous < RESTART_DEBOUNCE_MS) {
            log.debug("[comm-hub] 重启请求在防抖窗口内，跳过。reason={}", reason);
            return;
        }
        if (!lastRestartAt.compareAndSet(previous, now)) {
            return;
        }
        log.warn("[comm-hub] 触发 MQTT 连接重建，reason={}", reason);
        Thread restartThread = new Thread(mqttConfig::restartSubscribeConnection, "mqtt-restart");
        restartThread.setDaemon(true);
        restartThread.start();
    }
}
