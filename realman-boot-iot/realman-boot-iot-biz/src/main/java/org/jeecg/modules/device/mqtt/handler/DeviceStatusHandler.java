package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 设备 keepalive / status/report 处理器（Topic: device/{deviceCode}/status/report）
 *
 * <p>职责：维护 Redis 在线态缓存（Key TTL + 在线集合），供离线判定使用。
 * 不做解密、DB、WebSocket、历史上报。
 *
 * <p>由 {@link org.jeecg.modules.device.mqtt.handler.MqttMessageDispatcher} 投递到
 * {@code keepaliveExecutor}，不占用 {@code deviceTaskExecutor}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusHandler {

    private static final long PRESENCE_TTL_MINUTES =
            DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1;

    /** 占位值：离线判定只依赖 Key 是否存在及 TTL，不解析 payload */
    private static final String PRESENCE_PLACEHOLDER = "{\"keepalive\":true}";

    private final StringRedisTemplate redisTemplate;

    /**
     * 刷新设备在线态：Key 不存在则创建并设 TTL，存在则续期；同时加入在线集合。
     */
    public void refreshPresence(String deviceCode, String payload) {
        log.info("[StatusHandler] [{}]设备上报状态信息：{}, 刷新设备在线态", deviceCode, payload);
        try {
            touchPresence(deviceCode);
        } catch (Exception e) {
            log.warn("[StatusHandler] Redis presence 失败 deviceCode={}", deviceCode, e);
        }
    }

    /** 兼容旧调用名 */
    public void refreshKeepalivePresence(String deviceCode, String payload) {
        refreshPresence(deviceCode, payload);
    }

    private void touchPresence(String deviceCode) {
        String statusKey = DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode;
        long ttlSeconds = PRESENCE_TTL_MINUTES * 60;
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            conn.setEx(statusKey, ttlSeconds, PRESENCE_PLACEHOLDER);
            conn.sAdd(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            return null;
        });
    }
}
