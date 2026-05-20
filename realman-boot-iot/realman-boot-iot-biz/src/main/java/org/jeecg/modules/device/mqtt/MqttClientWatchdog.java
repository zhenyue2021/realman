package org.jeecg.modules.device.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <p>注意：此任务监控的是 JVM 本地的 Paho 客户端状态，每个 Pod 独立运行，
 * 无需分布式锁（各 Pod 监控自己的客户端，互不干扰）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttClientWatchdog {

    private final MqttClient mqttClient;

    /**
     * 消息空闲超时阈值（秒）。
     * 生产环境设备每隔 N 秒上报心跳，若平台超过此阈值没有收到任何消息，
     * 说明 Paho 可能已僵死，触发强制重连。
     * 默认 300s（5分钟），可通过配置覆盖：mqtt.watchdog.idle-timeout-seconds
     */
    @Value("${mqtt.watchdog.idle-timeout-seconds:300}")
    private long idleTimeoutSeconds;

    @Scheduled(fixedDelay = 60_000)
    public void watchdog() {
        long idleMs = System.currentTimeMillis() - MqttMessageDispatcher.lastReceivedTs.get();
        long thresholdMs = idleTimeoutSeconds * 1000;

        if (idleMs < thresholdMs) {
            return;
        }

        if (!mqttClient.isConnected()) {
            // Paho 已感知断连但仍未重连成功（可能在退避等待中），主动 kick 一次
            log.warn("[MqttWatchdog] {}s 无消息且客户端已断连，主动触发重连", idleMs / 1000);
            try {
                mqttClient.reconnect();
            } catch (MqttException e) {
                log.warn("[MqttWatchdog] 触发重连失败，将继续等待 Paho 自动重连", e);
            }
            return;
        }

        // 客户端"看起来已连接"但不收消息 → 僵死状态
        log.warn("[MqttWatchdog] {}s 无消息但客户端仍显示已连接，疑似僵死，强制重连", idleMs / 1000);
        try {
            // disconnectForcibly 强制关闭 TCP 连接
            mqttClient.disconnectForcibly(0L, 2000L);
            // 部分 Paho 版本在 disconnectForcibly 后不自动重连，显式调用保底
            mqttClient.reconnect();
            log.info("[MqttWatchdog] 强制重连已触发，等待 connectComplete 回调重订阅");
        } catch (MqttException e) {
            log.error("[MqttWatchdog] 强制重连失败", e);
        }
    }
}
