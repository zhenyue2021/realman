package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时校验 Redis 超时配置是否生效（防止 test/Nacos 遗漏导致 pipeline 永久阻塞）。
 */
@Slf4j
@Component
public class RedisTimeoutValidator {

    @Value("${spring.data.redis.timeout:}")
    private String commandTimeout;

    @Value("${spring.data.redis.lettuce.pool.max-wait:}")
    private String poolMaxWait;

    @Value("${spring.data.redis.lettuce.pool.max-active:8}")
    private int poolMaxActive;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        log.info("[Redis] 连接池 max-active={} command-timeout={} pool-max-wait={}",
                poolMaxActive,
                commandTimeout.isBlank() ? "未配置" : commandTimeout + "ms",
                poolMaxWait.isBlank() ? "未配置" : poolMaxWait + "ms");
        if (commandTimeout.isBlank()) {
            log.warn("[Redis] spring.data.redis.timeout 未配置，Lettuce 命令可能无限阻塞；"
                    + "建议设置 timeout: 3000（ms）");
        }
        if (poolMaxWait.isBlank()) {
            log.warn("[Redis] spring.data.redis.lettuce.pool.max-wait 未配置，"
                    + "连接池耗尽时线程可能无限等待；建议设置 max-wait: 2000（ms）");
        }
    }
}
