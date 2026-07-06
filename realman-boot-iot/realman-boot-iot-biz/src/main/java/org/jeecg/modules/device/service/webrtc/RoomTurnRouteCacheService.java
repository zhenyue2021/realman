package org.jeecg.modules.device.service.webrtc;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.config.WebRtcProperties;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 房间 TURN/信令智能调度结果 Redis 缓存（TTL 默认 12h，由 XXL-Job 每日清理，调用方按需重新拉取）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomTurnRouteCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TurnRouterClient turnRouterClient;
    private final WebRtcProperties webRtcProperties;

    public RoomTurnRouteCache get(String masterCode) {
        if (masterCode == null || masterCode.isBlank()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(masterCode));
            if (json != null) {
                return objectMapper.readValue(json, RoomTurnRouteCache.class);
            }
        } catch (Exception e) {
            log.warn("[TurnRouteCache] 读取失败 masterCode={}", masterCode, e);
        }
        return null;
    }

    public void put(String masterCode, RoomTurnRouteCache cache) {
        if (masterCode == null || masterCode.isBlank() || cache == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(cache);
            redisTemplate.opsForValue().set(
                    cacheKey(masterCode),
                    json,
                    routeCacheTtlHours(),
                    TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[TurnRouteCache] 写入失败 masterCode={}", masterCode, e);
        }
    }

    public void evict(String masterCode) {
        if (masterCode == null || masterCode.isBlank()) {
            return;
        }
        redisTemplate.delete(cacheKey(masterCode));
    }

    public RoomTurnRouteCache getOrFetch(String masterCode,
                                         String roomId,
                                         String robotProvince,
                                         String robotCity,
                                         String browserProvince,
                                         String browserCity) {
        RoomTurnRouteCache cached = get(masterCode);
        if (cached != null) {
            return cached;
        }
        TurnRouteResult route = turnRouterClient.route(
                roomId, robotProvince, robotCity, browserProvince, browserCity);
        RoomTurnRouteCache cache = new RoomTurnRouteCache(
                route.getServerIp(), route.getServerPort(), route.getSignalKey());
        put(masterCode, cache);
        return cache;
    }

    /**
     * 清理全部 TURN 路由缓存 Key，不主动调用 turn_router；后续由 {@link #getOrFetch} 按需重建。
     */
    public int evictAll() {
        Set<String> keys = redisTemplate.keys(DeviceConstant.RedisKey.ROOM_TURN_ROUTE_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Set<String> toDelete = new HashSet<>(keys);
        toDelete.remove(DeviceConstant.RedisKey.ROOM_TURN_ROUTE_REFRESH_LOCK);
        if (toDelete.isEmpty()) {
            return 0;
        }
        Long deleted = redisTemplate.delete(toDelete);
        return deleted == null ? 0 : deleted.intValue();
    }

    /**
     * XXL-Job 触发的全量清理：受配置开关控制，分布式锁保证多节点仅一个实例执行。
     *
     * @return 实际删除的 Key 数量；未执行时返回 0
     */
    public int evictAllScheduled() {
        if (!webRtcProperties.getTurnRouter().isRouteCacheMidnightEvict()) {
            log.debug("[TurnRouteCache] route-cache-midnight-evict=false，跳过清理");
            return 0;
        }
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                DeviceConstant.RedisKey.ROOM_TURN_ROUTE_REFRESH_LOCK,
                "1",
                5L,
                TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("[TurnRouteCache] 其他节点正在执行清理，跳过");
            return 0;
        }
        int deleted = evictAll();
        log.info("[TurnRouteCache] 清理完成 deleted={}", deleted);
        return deleted;
    }

    private long routeCacheTtlHours() {
        return Math.max(webRtcProperties.getTurnRouter().getRouteCacheTtlHours(), 1);
    }

    private static String cacheKey(String masterCode) {
        return DeviceConstant.RedisKey.ROOM_TURN_ROUTE_PREFIX + masterCode;
    }
}
