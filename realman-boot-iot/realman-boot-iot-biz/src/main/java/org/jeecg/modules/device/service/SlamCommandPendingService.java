package org.jeecg.modules.device.service;

import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SLAM 指令首次响应等待服务
 *
 * <p>以 commandId 为 key，存放平台发出 slam/request 后挂起的 {@link CompletableFuture}。
 * <ul>
 *   <li>由 {@link IIotSlamCommandService#sendCommand} 在发送 MQTT 前调用 {@link #register(String)} 注册</li>
 *   <li>由 {@link IIotSlamCommandService#handleAck} 收到第一次 ack 后调用 {@link #complete} 解锁</li>
 *   <li>发送失败或等待超时时调用 {@link #completeExceptionally} 清理</li>
 * </ul>
 *
 * <p>设备收到请求后立即回复第一次响应（sequence=1）；若 total>1 则后续还会有更多响应，
 * 这些后续响应只更新 DB 记录，不再触发 Future（Future 在第一次响应时已移除）。
 */
@Service
public class SlamCommandPendingService {

    private final ConcurrentHashMap<String, CompletableFuture<MqttMessageModel.SlamAck>> pending
            = new ConcurrentHashMap<>();

    public CompletableFuture<MqttMessageModel.SlamAck> register(String commandId) {
        CompletableFuture<MqttMessageModel.SlamAck> future = new CompletableFuture<>();
        pending.put(commandId, future);
        return future;
    }

    /**
     * 收到 ack 时完成 Future 并移除（仅第一次有效，后续响应找不到 entry 则忽略）。
     *
     * @return 是否找到等待中的 Future
     */
    public boolean complete(String commandId, MqttMessageModel.SlamAck ack) {
        CompletableFuture<MqttMessageModel.SlamAck> future = pending.remove(commandId);
        if (future != null) {
            future.complete(ack);
            return true;
        }
        return false;
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<MqttMessageModel.SlamAck> future = pending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }
}
