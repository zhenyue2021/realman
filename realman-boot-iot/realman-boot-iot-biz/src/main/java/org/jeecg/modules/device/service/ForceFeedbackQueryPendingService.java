package org.jeecg.modules.device.service;

import org.jeecg.modules.device.vo.ForceFeedbackVO;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 力反馈参数查询等待服务
 *
 * <p>以 commandId 为 key，在平台发出查询指令后挂起请求，
 * 待设备通过 master/{controllerCode}/command/force-feedback/ack 回复后完成 Future。
 */
@Service
public class ForceFeedbackQueryPendingService {

    private final ConcurrentHashMap<String, CompletableFuture<ForceFeedbackVO>> pending
            = new ConcurrentHashMap<>();

    public CompletableFuture<ForceFeedbackVO> register(String commandId) {
        CompletableFuture<ForceFeedbackVO> future = new CompletableFuture<>();
        pending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, ForceFeedbackVO vo) {
        CompletableFuture<ForceFeedbackVO> future = pending.remove(commandId);
        if (future != null) {
            future.complete(vo);
            return true;
        }
        return false;
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<ForceFeedbackVO> future = pending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }
}
