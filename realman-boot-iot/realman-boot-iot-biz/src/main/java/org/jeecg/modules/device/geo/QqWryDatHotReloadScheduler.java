package org.jeecg.modules.device.geo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 与运维定时替换 {@code qqwry.dat} 配合：按间隔比对文件 mtime，必要时热加载内存库。
 * <p>
 * 分布式锁保证多节点只有一个节点执行热加载，TTL 略小于检查间隔防止锁泄漏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "device.mqtt-auth.ip-geo.qqwry.hot-reload", havingValue = "true")
public class QqWryDatHotReloadScheduler {

    private final QqWryIpGeoClient      qqWryIpGeoClient;
    private final StringRedisTemplate   stringRedisTemplate;

    private static final String LOCK_KEY     = "iot:geo:qqwry:reload:lock";
    /** TTL 比默认检查间隔（3600s）短 60s，防止锁过期前本节点宕机导致永久锁 */
    private static final long   LOCK_TTL_SEC = 3540L;

    @Scheduled(fixedDelayString = "${device.mqtt-auth.ip-geo.qqwry.reload-check-ms:3600000}")
    public void checkAndReload() {
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", LOCK_TTL_SEC, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("[QqWry热加载] 其他节点已持有锁，跳过本次检查");
            return;
        }
        qqWryIpGeoClient.tryReloadFromDiskIfModified();
    }
}
