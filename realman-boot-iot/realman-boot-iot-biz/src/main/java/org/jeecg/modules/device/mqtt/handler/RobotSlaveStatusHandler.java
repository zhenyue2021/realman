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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 机器人/主控设备原始状态上报处理器
 *
 * <p>Topic 格式：{deviceCode}/slave/states（机器人） | {deviceCode}/master/states（主控）
 *
 * <p>处理流程：解密 → 校验设备 → WebSocket 推送 → 写入 Redis 队列
 *
 * <p>持久化策略：上报数据追加到 Redis List（{@code iot:slave:pending:{deviceCode}}），
 * 同时将 deviceCode 记入 tracking Set（{@code iot:slave:pending:devices}）。
 * 由外部调度器（{@code DeviceSchedulerJob}）定期调用 {@link #flushPending()} 落库：
 * 每台设备的本轮所有上报数据序列化为 JSON 数组写一条记录。
 *
 * <p>集群安全保证：
 * <ul>
 *   <li>两个 Lua 脚本均为 Redis 原子操作，多节点同时 flush 时 tracking Set 只被一个节点摘出，
 *       天然互斥，不会重复写库。</li>
 *   <li>新上报在 tracking Set 被摘出后仍会重新写入 Set + List，等下一轮 flush 处理，不丢失。</li>
 * </ul>
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

    // -------------------------------------------------------------------------
    // Redis key 定义
    // -------------------------------------------------------------------------

    /** 上报数据 List key 前缀：{prefix}{deviceCode} */
    private static final String PENDING_KEY_PREFIX = "iot:slave:pending:";
    /** 待 flush 的 deviceCode tracking Set */
    private static final String PENDING_DEVICES_SET = "iot:slave:pending:devices";

    // -------------------------------------------------------------------------
    // Lua 脚本（Redis 原子操作）
    // -------------------------------------------------------------------------

    /**
     * 原子摘出 tracking Set：SMEMBERS + DEL。
     * 同一 flush 周期内多节点竞争时，只有先执行的节点能拿到成员列表，
     * 后执行的节点拿到空列表直接跳过，天然保证不重复处理。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> POP_DEVICES_SCRIPT = new DefaultRedisScript<>(
            "local m = redis.call('SMEMBERS', KEYS[1]); " +
            "if #m > 0 then redis.call('DEL', KEYS[1]) end; " +
            "return m",
            List.class
    );

    /**
     * 原子摘出设备 List：LRANGE 0 -1 + DEL。
     * 保证快照与清除原子完成：flush 期间新推入的数据若在 DEL 之前到达则包含在本次结果，
     * 若在 DEL 之后到达则留在新列表中等下一轮处理，两种情况均不丢失数据。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DRAIN_LIST_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('LRANGE', KEYS[1], 0, -1); " +
            "if #v > 0 then redis.call('DEL', KEYS[1]) end; " +
            "return v",
            List.class
    );

    // -------------------------------------------------------------------------
    // 本节点设备缓存（减少 flush 时的 DB 查询频率）
    // -------------------------------------------------------------------------

    /**
     * 设备实体本地缓存：key=deviceCode。
     * 设备信息变更概率极低，各节点独立缓存自己处理过的设备即可。
     * 缓存未命中时（如跨节点 flush）直接查库，每分钟一次，开销可接受。
     */
    private final ConcurrentHashMap<String, IotDevice> deviceCache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // 公开入口
    // -------------------------------------------------------------------------

    /** 处理机器人原始状态上报（{robotCode}/slave/states） */
    public void handle(String robotCode, String payload) {
        log.debug("[SlaveStatusHandler] slave上报 robotCode={}", robotCode);
        processStatus(robotCode, payload, deviceWebSocketServer::pushRobotStatus, true);
    }

    /** 处理主控设备原始状态上报（{robotCode}/master/states） */
    public void handleMasterStatus(String robotCode, String payload) {
        log.debug("[SlaveStatusHandler] master上报 robotCode={}", robotCode);
        String key = DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode;
        String masterCode = redisTemplate.opsForValue().get(key);
        if (masterCode == null) {
            log.warn("[MasterCommandHandler] 未找到机器人 {} 对应的主控缓存，忽略消息", robotCode);
            return;
        }
        // 机器人状态上报高频，仅在 TTL 低于 2h 时才续期，避免每次上报都调用 expire
        Long ttl = redisTemplate.getExpire(key, TimeUnit.HOURS);
        if (ttl != null && ttl < 2) {
            redisTemplate.expire(key, 24, TimeUnit.HOURS);
            redisTemplate.expire(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + masterCode, 24, TimeUnit.HOURS);
        }
        processStatus(masterCode, payload, deviceWebSocketServer::pushMasterStatus, false);
    }

    // -------------------------------------------------------------------------
    // 内部逻辑
    // -------------------------------------------------------------------------

    /**
     * 通用状态处理：解密 → 校验设备 → WebSocket 推送 → 可选写入 Redis 队列
     *
     * @param deviceCode 设备编码
     * @param payload    原始 Payload（可能加密）
     * @param wsPusher   WebSocket 推送方法引用
     * @param buffer     是否将上报数据追加到 Redis 待落库队列
     */
    private void processStatus(String deviceCode, String payload,
                                BiConsumer<String, String> wsPusher, boolean buffer) {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);

        // 设备校验：优先读本地缓存，缓存未命中才查库
        IotDevice device = deviceCache.computeIfAbsent(deviceCode, k ->
                deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, k)
                        .last("LIMIT 1")));
        if (device == null) {
            // computeIfAbsent 不缓存 null，下次仍会重试查库
            log.warn("[SlaveStatusHandler] 未知设备，忽略上报: {}", deviceCode);
            return;
        }

        wsPusher.accept(deviceCode, decrypted);

        if (buffer) {
            // 追加到 Redis List，并将 deviceCode 加入 tracking Set
            redisTemplate.opsForList().rightPush(PENDING_KEY_PREFIX + deviceCode, decrypted);
            redisTemplate.opsForSet().add(PENDING_DEVICES_SET, deviceCode);
        }
    }

    /**
     * 定时落库：将各设备本轮积累的所有上报数据序列化为 JSON 数组，每台设备写一条记录。
     *
     * <p>流程：
     * <ol>
     *   <li>原子摘出 tracking Set（{@link #POP_DEVICES_SCRIPT}），获取本轮 deviceCode 列表</li>
     *   <li>逐个原子摘出各设备 Redis List（{@link #DRAIN_LIST_SCRIPT}）</li>
     *   <li>将所有条目组装为 JSON 数组落库；单台设备失败不影响其他设备</li>
     * </ol>
     */
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
                        array.add(raw); // 解析失败以字符串节点兜底，不丢弃数据
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

    /** 加载设备实体：优先本地缓存，未命中时查库（flush 每分钟一次，开销可接受） */
    private IotDevice loadDevice(String deviceCode) {
        return deviceCache.computeIfAbsent(deviceCode, k ->
                deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, k)
                        .last("LIMIT 1")));
    }

    /**
     * 执行单条批量状态记录写库。
     * {@code rawData} 为本轮该设备所有上报数据的 JSON 数组；时间字段均取落库时的平台当前时间。
     */
    private void doPersist(IotDevice device, String rawData) {
        IotDeviceStatus s = new IotDeviceStatus();
        s.setDeviceId(device.getId());
        s.setDeviceCode(device.getDeviceCode());
        s.setRawData(rawData);
        LocalDateTime now = LocalDateTime.now();
        s.setReportTime(now);
        s.setReceiveTime(now);
        // 这种上报不记录，没有实际意义，已有状态上报逻辑（只是数据没那么全，且上报时间也没那么频繁）
//        statusMapper.insert(s);
    }
}
