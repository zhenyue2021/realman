package org.jeecg.modules.device.service;

import org.jeecg.modules.device.vo.SportSpeedVO;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运动与安全参数查询等待服务
 *
 * <p>以 commandId 为 key，在平台发出查询指令后挂起请求，
 * 待设备通过 master/{controllerCode}/command/sport-speed/ack 回复后完成 Future。
 */
@Service
public class SportSpeedQueryPendingService {

    private final ConcurrentHashMap<String, CompletableFuture<SportSpeedVO>> pending
            = new ConcurrentHashMap<>();

    public CompletableFuture<SportSpeedVO> register(String commandId) {
        CompletableFuture<SportSpeedVO> future = new CompletableFuture<>();
        pending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, SportSpeedVO vo) {
        CompletableFuture<SportSpeedVO> future = pending.remove(commandId);
        if (future != null) {
            future.complete(vo);
            return true;
        }
        return false;
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<SportSpeedVO> future = pending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }
}
