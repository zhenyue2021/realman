package org.jeecg.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 防止刷短信接口（只针对绑定手机号模板：SMS_175430166）
 *
 * 规则（Redis 分布式实现，集群安全）：
 * 1. 同一 IP，1 分钟内发短信不允许超过 5 次（滑动窗口 60s，key 自动过期重置）
 * 2. 同一 IP，1 分钟内累计请求超过 20 次，进入黑名单 24 小时
 *
 * Redis Key 说明：
 * - sms:limit:count:{ip}     → 请求计数器，TTL 60s，窗口到期自动清零
 * - sms:limit:blacklist:{ip} → 黑名单标记，TTL 86400s（24 小时后自动解除）
 *
 * 涉及接口：
 * /sys/sms
 * /desform/api/sendVerifyCode
 * /sys/sendChangePwdSms
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DySmsLimit {

    /** 1 分钟内单 IP 最大允许发送次数 */
    private static final long MAX_SEND_PER_MINUTE = 5;

    /** 1 分钟内单 IP 触发黑名单的请求次数阈值 */
    private static final long BLACKLIST_THRESHOLD = 20;

    /** 限流窗口时长（秒） */
    private static final long WINDOW_SECONDS = 60;

    /** 黑名单有效期（秒），24 小时后自动解除 */
    private static final long BLACKLIST_SECONDS = 86400;

    private static final String COUNT_PREFIX     = "sms:limit:count:";
    private static final String BLACKLIST_PREFIX = "sms:limit:blacklist:";

    private final RedisUtil redisUtil;

    /**
     * 判断当前 IP 是否允许发送短信。
     *
     * @param ip 客户端 IP
     * @return true=允许发送；false=拒绝
     */
    public boolean canSendSms(String ip) {
        String blacklistKey = BLACKLIST_PREFIX + ip;
        String countKey     = COUNT_PREFIX + ip;

        // 1. 黑名单检查
        if (redisUtil.hasKey(blacklistKey)) {
            log.error("[短信限流] IP: {} 在黑名单中，禁止发送", ip);
            return false;
        }

        // 2. 自增计数（INCR 原子操作，key 不存在时从 0 开始）
        long count = redisUtil.incr(countKey, 1);
        if (count == 1) {
            // 新窗口第一次请求，设置 60s 过期（窗口到期自动重置）
            redisUtil.expire(countKey, WINDOW_SECONDS);
        }
        log.info("[短信限流] IP: {}, 当前窗口请求次数: {}", ip, count);

        // 3. 超过黑名单阈值 → 拉黑 24 小时
        if (count >= BLACKLIST_THRESHOLD) {
            redisUtil.set(blacklistKey, "1", BLACKLIST_SECONDS);
            log.error("[短信限流] IP: {} 1分钟内请求{}次，已加入黑名单（24小时）", ip, count);
            return false;
        }

        // 4. 超过正常频率 → 拒绝但不拉黑
        if (count > MAX_SEND_PER_MINUTE) {
            log.warn("[短信限流] IP: {} 1分钟内请求超过{}次，请稍后重试", ip, MAX_SEND_PER_MINUTE);
            return false;
        }

        return true;
    }

    /**
     * 图片验证码验证成功后，清空当前 IP 的计数（允许重新发送）。
     *
     * @param ip 客户端 IP
     */
    public void clearSendSmsCount(String ip) {
        redisUtil.del(COUNT_PREFIX + ip);
        log.info("[短信限流] IP: {} 验证码校验通过，计数已重置", ip);
    }
}
