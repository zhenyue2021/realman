package org.jeecg.modules.device.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.config.MqttConfig;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MQTT 客户端存活监控（Watchdog）
 *
 * <p>问题背景：Paho v5 在 cleanStart=true + 自动重连场景下，偶现"僵死"状态——
 * EMQX 侧看到客户端已连接且订阅存在，但 Java 侧 messageArrived 回调不再触发。
 * 根本表现：设备持续上报，平台收不到任何消息，只能手动重启服务恢复。
 *
 * <p>检测机制：{@link MqttMessageDispatcher#lastReceivedTs} 记录最后一次收到消息的时间戳。
 * 本 Watchdog 每 60s 检查一次，若超过阈值时间无消息且客户端"看起来已连接"，
 * 则强制关闭 TCP 连接，触发 Paho 内置的自动重连，重连成功后 connectComplete 回调
 * 会重新订阅全部业务 Topic。
 *
 * <p>实现说明：使用自管理的 {@link ScheduledExecutorService}（非 Spring @Scheduled），
 * 避免依赖 Spring 调度基础设施的初始化时序，保证 Watchdog 在任何环境下都可靠运行。
 * 每个 Pod 独立监控自己的 Paho 客户端，无需分布式锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttClientWatchdog {

    /** 平台自检心跳 Topic，与 MqttConfig 订阅列表和 MqttMessageDispatcher 过滤保持一致 */
    static final String HEARTBEAT_TOPIC = "iot-platform/heartbeat";

    private final MqttConfig mqttConfig;
    private final MqttPublisher mqttPublisher;

    /**
     * 消息空闲超时阈值（秒）。
     * 若平台超过此阈值没有收到任何消息（含自检心跳），触发强制重连。
     * 默认 90s = 心跳间隔 45s × 2，可通过配置覆盖：mqtt.watchdog.idle-timeout-seconds
     */
    @Value("${mqtt.watchdog.idle-timeout-seconds:90}")
    private long idleTimeoutSeconds;

    @PostConstruct
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mqtt-watchdog");
            t.setDaemon(true);
            return t;
        });
        // 心跳发布：每 45s 发一次，订阅客户端收到后刷新 lastReceivedTs；
        // 即使 0 台设备在线，watchdog 也不会误判为僵死。首次延迟 15s，等待 MQTT 客户端完全就绪。
        scheduler.scheduleWithFixedDelay(this::publishHeartbeat, 15, 45, TimeUnit.SECONDS);
        // 僵死检测：首次 15s 后开始，之后每 30s 检查（与心跳首次延迟对齐）
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
            long idleMs = System.currentTimeMillis() - MqttMessageDispatcher.lastReceivedTs.get();
            long thresholdMs = idleTimeoutSeconds * 1000;

            if (idleMs < thresholdMs) {
                return;
            }

            if (!mqttConfig.isClientConnected()) {
                log.warn("[MqttWatchdog] {}s 无消息且客户端已断连，重建连接", idleMs / 1000);
            } else {
                // 客户端"看起来已连接"但不收消息 → 僵死状态
                // reconnect() 复用损坏的 Paho 内部线程无效，必须通过 restartConnection()
                // 调用 connect() 创建全新的 CommsSender/CommsReceiver/CommsCallback 线程
                log.warn("[MqttWatchdog] {}s 无消息但客户端仍显示已连接，疑似僵死，重建连接", idleMs / 1000);
            }
            Thread restartThread = new Thread(() -> {
                try {
                    mqttConfig.restartConnection();
                } catch (Exception e) {
                    log.error("[MqttWatchdog] 重建连接异常", e);
                }
            }, "mqtt-watchdog-restart");
            restartThread.setDaemon(true);
            restartThread.start();
        } catch (Exception e) {
            // 捕获所有异常，防止单次检测失败导致 Executor 停止调度
            log.error("[MqttWatchdog] 检测异常", e);
        }
    }
}
