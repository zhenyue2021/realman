package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备状态上报消息处理器（Topic: device/{deviceCode}/status/report）
 *
 * <p>双投递（由 {@link MqttMessageDispatcher} 保证）：
 * <ol>
 *   <li>{@link #refreshKeepalivePresence} — keepaliveExecutor：仅 Redis TTL，队列满也不影响离线判定</li>
 *   <li>{@link #handle} — deviceTaskExecutor：完整业务（日志 / WebSocket / 异步 DB）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusHandler {

    private final IotDeviceMapper deviceMapper;
    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeviceWebSocketServer webSocketServer;
    private final DeviceStatusPersistenceService persistenceService;

    private static final long PRESENCE_TTL_MINUTES =
            DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1;

    private final ConcurrentHashMap<String, IotDevice> deviceCache    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>      deviceCacheTs  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>      lastDbUpdateTs = new ConcurrentHashMap<>();

    private static final long DEVICE_CACHE_TTL_MS   = 5 * 60 * 1000L;
    private static final long DB_UPDATE_INTERVAL_MS = 50_000L;

    // ── keepalive 快路径（keepaliveExecutor）────────────────────────────────

    /**
     * 刷新 keepalive 存活性（Redis Key + 在线集合），供离线判定使用。
     *
     * <p>写入占位值即可；{@link #handle} 会用真实内容覆盖。
     */
    public void refreshKeepalivePresence(String deviceCode, String payload) {
        touchPresence(deviceCode, "{\"keepalive\":true}");
    }

    // ── 主处理流程（deviceTaskExecutor）────────────────────────────────────

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);

        // 只解析一次：keepalive 判断与字段提取共用同一个 JsonNode
        JsonNode node = objectMapper.readTree(decrypted);

        if (isKeepaliveMessage(node)) {
            log.info ("[StatusHandler] keepalive 消息上报 deviceCode={} message={}", deviceCode, decrypted);
            touchPresence(deviceCode, decrypted);
            return;
        }

        log.info("[StatusHandler] 设备上报消息体: {}", decrypted);

        MqttMessageModel.StatusReport report =
                objectMapper.treeToValue(node, MqttMessageModel.StatusReport.class);

        touchPresence(deviceCode, decrypted);

        IotDevice device = getCachedDevice(deviceCode);
        if (device == null) {
            log.warn("[StatusHandler] 未知设备: {}", deviceCode);
            return;
        }

        webSocketServer.pushDeviceStatus(deviceCode, decrypted);
        persistenceService.persistHistory(device, report, decrypted);
        scheduleDbUpdateIfNeeded(device, report);
    }

    public void evictDeviceCache(String deviceCode) {
        deviceCache.remove(deviceCode);
        deviceCacheTs.remove(deviceCode);
        lastDbUpdateTs.remove(deviceCode);
    }

    private boolean isKeepaliveMessage(JsonNode node) {
        return node.hasNonNull("message")
                && "keepalive".equalsIgnoreCase(node.get("message").asText());
    }

    private void touchPresence(String deviceCode, String redisValue) {
        String statusKey = DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode;
        long ttlSeconds = PRESENCE_TTL_MINUTES * 60;
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            conn.setEx(statusKey, ttlSeconds, redisValue);
            conn.sAdd(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            return null;
        });
    }

    private IotDevice getCachedDevice(String deviceCode) {
        Long cachedAt = deviceCacheTs.get(deviceCode);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < DEVICE_CACHE_TTL_MS) {
            IotDevice hit = deviceCache.get(deviceCode);
            if (hit != null) {
                return hit;
            }
        }
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
        if (device != null) {
            deviceCache.put(deviceCode, device);
            deviceCacheTs.put(deviceCode, System.currentTimeMillis());
        }
        return device;
    }

    private void scheduleDbUpdateIfNeeded(IotDevice cached, MqttMessageModel.StatusReport report) {
        long now = System.currentTimeMillis();
        Long lastUpdate = lastDbUpdateTs.get(cached.getDeviceCode());

        boolean statusChanged = !Objects.equals(cached.getStatus(), DeviceConstant.DeviceStatus.ONLINE);
        boolean locationChanged = (report.getLongitude() != null
                && !Objects.equals(report.getLongitude(), cached.getLongitude()))
                || (report.getLatitude() != null
                && !Objects.equals(report.getLatitude(), cached.getLatitude()));
        boolean intervalExpired = lastUpdate == null || now - lastUpdate > DB_UPDATE_INTERVAL_MS;

        if (!statusChanged && !locationChanged && !intervalExpired) {
            return;
        }

        IotDevice update = new IotDevice();
        update.setId(cached.getId());
        update.setStatus(DeviceConstant.DeviceStatus.ONLINE);
        update.setLastOnlineTime(LocalDateTime.now());
        if (report.getLongitude() != null) {
            update.setLongitude(report.getLongitude());
        }
        if (report.getLatitude() != null) {
            update.setLatitude(report.getLatitude());
        }
        persistenceService.updateDeviceOnline(update);
        lastDbUpdateTs.put(cached.getDeviceCode(), now);

        cached.setStatus(DeviceConstant.DeviceStatus.ONLINE);
        if (report.getLongitude() != null) {
            cached.setLongitude(report.getLongitude());
        }
        if (report.getLatitude() != null) {
            cached.setLatitude(report.getLatitude());
        }
    }
}
