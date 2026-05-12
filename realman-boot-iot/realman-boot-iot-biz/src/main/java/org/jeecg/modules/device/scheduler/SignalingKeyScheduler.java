package org.jeecg.modules.device.scheduler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.service.signaling.SignalingKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 信令服务器房间密钥管理任务
 *
 * <p>两个独立触发点：
 * <ol>
 *   <li><b>启动初始化</b>：{@code @EventListener(ApplicationReadyEvent)} —— 服务就绪后立即执行一次，
 *       确保密钥在首次 WebRTC 请求前已推送到信令服务器。</li>
 *   <li><b>定时刷新</b>：{@code @XxlJob("signalingKeyRenewJob")} —— 由 XXL-Job Admin 统一调度，
 *       建议 Cron {@code 0 0 2 * * ?}（每天凌晨 2:00），路由策略选"第一个"或"故障转移"。</li>
 * </ol>
 *
 * <p>集群安全：两个触发点各持独立的 Redis 分布式锁，确保同一批次只有一个节点执行推送。
 * 即使 XXL-Job 路由策略设为"广播"，也能通过锁保证幂等。
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
    /**
     * 锁 TTL（秒）。
     * generateAndPush() 最长耗时：连接超时 5s + 读超时 10s + Redis 写入 <1s ≈ 16s。
     * 设为 60s 保留充足余量，同时避免锁长期占用影响故障恢复。
     */
    private static final long LOCK_TTL_SECONDS = 60L;

    /**
     * 是否在应用启动时自动推送密钥，默认 true。
     * 测试环境可在 application-dev.yml 中设为 false 跳过推送：
     * {@code webrtc.signaling.server.auto-push.enabled=false}
     */
    @Value("${webrtc.signaling.server.auto-push.enabled:true}")
    private boolean autoPushOnStartup;

    /**
     * 服务启动就绪后初始化密钥（不阻塞启动流程）。
     *
     * <p>使用 {@code ApplicationReadyEvent} 而非 {@code @PostConstruct}，
     * 确保 Redis 连接池和 HTTP 客户端均已完全就绪。
     * 集群部署时通过分布式锁保证只有一个节点执行。
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
     * 每天凌晨 2:00 重新生成并推送密钥（XXL-Job 管理）。
     *
     * <p><b>XXL-Job 配置：</b>
     * <ul>
     *   <li>Handler Name：{@code signalingKeyRenewJob}</li>
     *   <li>Cron：{@code 0 0 2 * * ?}</li>
     *   <li>路由策略：第一个 / 故障转移（推荐）；广播模式下依赖分布式锁保证幂等</li>
     * </ul>
     */
    @XxlJob("signalingKeyRenewJob")
    public void renewDaily() {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(RENEW_LOCK_KEY, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            XxlJobHelper.log("[Signaling] 其他节点已执行密钥刷新，跳过");
            return;
        }
        XxlJobHelper.log("[Signaling] [XXL-Job] signalingKeyRenewJob 触发，刷新信令服务器密钥");
        signalingKeyService.generateAndPush();
    }
}
