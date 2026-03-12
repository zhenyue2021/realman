package org.jeecg.modules.device.service;

import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主控“当前关联设备信息”查询等待服务
 *
 * <p>以 commandId 为 key，存放“平台→主控”一次查询过程对应的 {@link CompletableFuture}。
 * <ul>
 *   <li>由 Web 接口在发送 AssociatedDeviceQuery 前调用 {@link #register(String)} 注册 Future</li>
 *   <li>由 {@code ControllerAssociatedDeviceResponseHandler} 在收到 AssociatedDeviceResponse 后调用 {@link #complete(String, MqttMessageModel.AssociatedDeviceResponse)}</li>
 *   <li>超时或发送失败时由调用方调用 {@link #completeExceptionally(String, Throwable)}</li>
 * </ul>
 */
@Service
public class ControllerAssociatedDevicePendingService {

    private final ConcurrentHashMap<String, CompletableFuture<MqttMessageModel.AssociatedDeviceResponse>> pending
            = new ConcurrentHashMap<>();

    public CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> register(String commandId) {
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = new CompletableFuture<>();
        pending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, MqttMessageModel.AssociatedDeviceResponse resp) {
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = pending.remove(commandId);
        if (future != null) {
            future.complete(resp);
            return true;
        }
        return false;
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<MqttMessageModel.AssociatedDeviceResponse> future = pending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }
}

