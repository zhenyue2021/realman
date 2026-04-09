package org.jeecg.modules.device.service.signaling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 信令服务器房间密钥定时刷新任务
 *
 * <p>触发时机：
 * <ul>
 *   <li>应用启动就绪后立即执行一次（可通过 {@code webrtc.signaling.server.auto-push.enabled=false} 关闭）</li>
 *   <li>每天凌晨 2:00 定时重新生成并推送（密钥有效期 24h，TTL 设为 26h 留有余量）</li>
 * </ul>
 *
 * <p>集群安全：通过 Redis 分布式锁确保同一时刻只有一个节点执行推送，避免多节点并发写入不同 key
 * 导致信令服务器与 Redis 缓存不一致。
 *
 * <p>前置条件：主启动类须声明 {@code @EnableScheduling}（已在 {@code RealmanDeviceApplication} 中声明）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalingKeyScheduler {

    private final SignalingKeyService signalingKeyService;
    private final StringRedisTemplate redisTemplate;

    /** 启动初始化分布式锁 Key */
    private static final String INIT_LOCK_KEY   = "iot:signaling:init:lock";
    /** 定时刷新分布式锁 Key */
    private static final String RENEW_LOCK_KEY  = "iot:signaling:renew:lock";
    /** 锁 TTL（秒）：推送完成前锁自动持有，防止其他节点重复执行 */
    private static final long   LOCK_TTL_SECONDS = 600L;

    /**
     * 是否在应用启动时自动推送密钥，默认 true。
     * 测试环境可在 application-dev.yml 中设为 false 跳过推送。
     */
    @Value("${webrtc.signaling.server.auto-push.enabled:true}")
    private boolean autoPushOnStartup;

    /**
     * 应用就绪后初始化密钥（不阻塞启动流程）
     *
     * <p>使用 ApplicationReadyEvent 而非 @PostConstruct，确保 Redis / HTTP 连接均已就绪。
     * 集群模式下通过分布式锁保证只有一个节点执行。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initOnStartup() {
        if (!autoPushOnStartup) {
            log.info("[Signaling] auto-push 已关闭，跳过启动时密钥初始化");
            return;
        }
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(INIT_LOCK_KEY, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("[Signaling] 其他节点已完成启动初始化，跳过");
            return;
        }
        log.info("[Signaling] 应用启动，初始化信令服务器密钥");
        signalingKeyService.generateAndPush();
    }

    /**
     * 每天凌晨 2:00 重新生成密钥
     *
     * <p>cron 表达式：{@code 0 0 2 * * ?} = 每天 02:00:00
     * 集群模式下通过分布式锁保证只有一个节点执行。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void renewDaily() {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(RENEW_LOCK_KEY, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[Signaling] 其他节点已执行密钥刷新，跳过");
            return;
        }
        log.info("[Signaling] 定时任务触发，刷新信令服务器密钥");
        signalingKeyService.generateAndPush();
    }
}
