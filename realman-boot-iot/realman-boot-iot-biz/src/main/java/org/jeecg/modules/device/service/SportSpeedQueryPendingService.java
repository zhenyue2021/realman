package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.vo.SportSpeedVO;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运动与安全参数查询等待服务
 *
 * <p>集群安全：通过 Redis Pub/Sub 完成跨节点 Future 通知，详见 {@link DeviceCameraStreamPendingService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SportSpeedQueryPendingService implements MessageListener {

    public static final String CHANNEL_PREFIX = "iot:pending:sport-speed:";

    private final ConcurrentHashMap<String, CompletableFuture<SportSpeedVO>> localPending
            = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CompletableFuture<SportSpeedVO> register(String commandId) {
        CompletableFuture<SportSpeedVO> future = new CompletableFuture<>();
        localPending.put(commandId, future);
        return future;
    }

    public boolean complete(String commandId, SportSpeedVO vo) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + commandId,
                    objectMapper.writeValueAsString(vo));
            return true;
        } catch (Exception e) {
            log.error("[SportSpeedPending] Redis 发布失败: commandId={}", commandId, e);
            CompletableFuture<SportSpeedVO> future = localPending.remove(commandId);
            if (future != null) { future.complete(vo); }
            return false;
        }
    }

    public void completeExceptionally(String commandId, Throwable ex) {
        CompletableFuture<SportSpeedVO> future = localPending.remove(commandId);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String commandId = channel.substring(CHANNEL_PREFIX.length());
        CompletableFuture<SportSpeedVO> future = localPending.remove(commandId);
        if (future != null) {
            try {
                future.complete(objectMapper.readValue(message.getBody(), SportSpeedVO.class));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
