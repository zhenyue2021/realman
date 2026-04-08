package org.jeecg.modules.device.service.signaling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 信令服务器房间密钥定时刷新任务
 *
 * <p>触发时机：
 * <ul>
 *   <li>应用启动就绪后立即执行一次（保证服务启动时密钥即有效）</li>
 *   <li>每天凌晨 2:00 定时重新生成并推送（密钥有效期 24h，TTL 设为 26h 留有余量）</li>
 * </ul>
 *
 * <p>前置条件：主启动类须声明 {@code @EnableScheduling}（已在 {@code RealmanDeviceApplication} 中声明）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalingKeyScheduler {

    private final SignalingKeyService signalingKeyService;

    /**
     * 应用就绪后初始化密钥（异步，不阻塞启动流程）
     *
     * <p>使用 ApplicationReadyEvent 而非 @PostConstruct，确保 Redis / HTTP 连接均已就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initOnStartup() {
        log.info("[Signaling] 应用启动，初始化信令服务器密钥");
        signalingKeyService.generateAndPush();
    }

    /**
     * 每天凌晨 2:00 重新生成密钥
     *
     * <p>cron 表达式：{@code 0 0 2 * * ?} = 每天 02:00:00
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void renewDaily() {
        log.info("[Signaling] 定时任务触发，刷新信令服务器密钥");
        signalingKeyService.generateAndPush();
    }
}
