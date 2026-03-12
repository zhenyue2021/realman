package org.jeecg.modules.device.service;

import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.handler.DeviceCameraStreamResponseHandler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摄像头流地址查询等待服务
 *
 * <p>以 commandId 为 key，存放“平台→机器人”一次查询过程对应的 {@link CompletableFuture}。
 * <ul>
 *   <li>由 IotDeviceServiceImpl.getCameraStreams(...) 在发送 CameraStreamQuery 前调用 {@link #register(String)}</li>
 *   <li>由 {@link DeviceCameraStreamResponseHandler} 在收到 CameraStreamResponse 后调用 {@link #complete(String, java.util.List)}</li>
 *   <li>超时或发送失败时由 Service 调用 {@link #completeExceptionally(String, Throwable)}</li>
 * </ul>
 *
 * <p>注意：所有路径都会从内部 {@link ConcurrentHashMap} 中移除 entry，避免 Future 泄漏。
 */
@Service
public class DeviceCameraStreamPendingService {

    private final ConcurrentHashMap<String, CompletableFuture<List<MqttMessageModel.CameraInfo>>> pending
            = new ConcurrentHashMap<>();

    public CompletableFuture<List<MqttMessageModel.CameraInfo>> register(String commandId) {
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future = new CompletableFuture<>();
        pending.put(commandId, future);
        return future;
    }

    /**
     * 机器人响应到达时，完成对应的 Future 并移除
     *
     * @return 是否找到等待中的 Future
     */
    public boolean complete(String commandId, List<MqttMessageModel.CameraInfo> cameras) {
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future = pending.remove(commandId);
        if (future != null) {
            future.complete(cameras);
            return true;
        }
        return false;
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future = pending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }
}
