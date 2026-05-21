package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 机器人/主控设备原始状态上报处理器
 *
 * <p>Topic 格式：{deviceCode}/slave/states（机器人） | {deviceCode}/master/states（主控）
 *
 * <p>处理流程：解密 → 校验设备 → Redis 队列（原子写入）→ 异步 WebSocket 推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
public class RobotSlaveStatusHandler {

    private final DeviceWebSocketServer deviceWebSocketServer;
    private final ObjectMapper objectMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceStatusMapper statusMapper;
    private final CommandEncryptService encryptService;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("deviceNotifyExecutor")
    private Executor deviceNotifyExecutor;

    private static final String PENDING_KEY_PREFIX = "iot:slave:pending:";
    private static final String PENDING_DEVICES_SET = "iot:slave:pending:devices";

    private static final RedisScript<Long> APPEND_PENDING_SCRIPT = new DefaultRedisScript<>(
            "redis.call('RPUSH', KEYS[1], ARGV[1]); " +
            "redis.call('SADD', KEYS[2], ARGV[2]); " +
            "return 1",
            Long.class
    );

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> POP_DEVICES_SCRIPT = new DefaultRedisScript<>(
            "local m = redis.call('SMEMBERS', KEYS[1]); " +
            "if #m > 0 then redis.call('DEL', KEYS[1]) end; " +
            "return m",
            List.class
    );

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DRAIN_LIST_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('LRANGE', KEYS[1], 0, -1); " +
            "if #v > 0 then redis.call('DEL', KEYS[1]) end; " +
            "return v",
            List.class
    );

    private final ConcurrentHashMap<String, IotDevice> deviceCache = new ConcurrentHashMap<>();

    public void handle(String robotCode, String payload) {
        log.debug("[SlaveStatusHandler] slave上报 robotCode={}", robotCode);
        processStatus(robotCode, payload, deviceWebSocketServer::pushRobotStatus, true);
    }

    public void handleMasterStatus(String robotCode, String payload) {
        log.debug("[SlaveStatusHandler] master上报 robotCode={}", robotCode);
        String key = DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode;
        String masterCode = redisTemplate.opsForValue().get(key);
        if (masterCode == null) {
            log.warn("[MasterCommandHandler] 未找到机器人 {} 对应的主控缓存，忽略消息", robotCode);
            return;
        }
        Long ttl = redisTemplate.getExpire(key, TimeUnit.HOURS);
        if (ttl != null && ttl < 2) {
            redisTemplate.expire(key, 24, TimeUnit.HOURS);
            redisTemplate.expire(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + masterCode, 24, TimeUnit.HOURS);
        }
        processStatus(masterCode, payload, deviceWebSocketServer::pushMasterStatus, false);
    }

    private void processStatus(String deviceCode, String payload,
                                BiConsumer<String, String> wsPusher, boolean buffer) {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);

        IotDevice device = deviceCache.computeIfAbsent(deviceCode, k ->
                deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, k)
                        .last("LIMIT 1")));
        if (device == null) {
            log.warn("[SlaveStatusHandler] 未知设备，忽略上报: {}", deviceCode);
            return;
        }

        if (buffer) {
            redisTemplate.execute(APPEND_PENDING_SCRIPT,
                    List.of(PENDING_KEY_PREFIX + deviceCode, PENDING_DEVICES_SET),
                    decrypted, deviceCode);
        }

        final String code = deviceCode;
        final String statusJson = decrypted;
        deviceNotifyExecutor.execute(() -> wsPusher.accept(code, statusJson));
    }

    public void flushPending() {
        @SuppressWarnings("unchecked")
        List<String> deviceCodes = redisTemplate.execute(POP_DEVICES_SCRIPT,
                List.of(PENDING_DEVICES_SET));
        if (deviceCodes == null || deviceCodes.isEmpty()) {
            return;
        }

        int flushedDevices = 0;
        for (String deviceCode : deviceCodes) {
            try {
                @SuppressWarnings("unchecked")
                List<String> raws = redisTemplate.execute(DRAIN_LIST_SCRIPT,
                        List.of(PENDING_KEY_PREFIX + deviceCode));
                if (raws == null || raws.isEmpty()) {
                    continue;
                }

                IotDevice device = loadDevice(deviceCode);
                if (device == null) {
                    log.warn("[SlaveStatusHandler] 未知设备，跳过落库: {}", deviceCode);
                    continue;
                }

                ArrayNode array = objectMapper.createArrayNode();
                for (String raw : raws) {
                    try {
                        array.add(objectMapper.readTree(raw));
                    } catch (Exception e) {
                        array.add(raw);
                    }
                }
                doPersist(device, objectMapper.writeValueAsString(array));
                flushedDevices++;
            } catch (Exception ex) {
                log.error("[SlaveStatusHandler] 落库失败 deviceCode={}", deviceCode, ex);
            }
        }
        log.debug("[SlaveStatusHandler] 定时落库，本轮设备数={}", flushedDevices);
    }

    private IotDevice loadDevice(String deviceCode) {
        return deviceCache.computeIfAbsent(deviceCode, k ->
                deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, k)
                        .last("LIMIT 1")));
    }

    private void doPersist(IotDevice device, String rawData) {
        IotDeviceStatus s = new IotDeviceStatus();
        s.setDeviceId(device.getId());
        s.setDeviceCode(device.getDeviceCode());
        s.setRawData(rawData);
        LocalDateTime now = LocalDateTime.now();
        s.setReportTime(now);
        s.setReceiveTime(now);
    }
}
