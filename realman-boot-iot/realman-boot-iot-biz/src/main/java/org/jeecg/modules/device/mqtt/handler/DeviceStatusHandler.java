package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.TimeUnit;

/**
 * 设备状态上报消息处理器（Topic: device/{deviceCode}/status/report）
 *
 * <p>处理流程：
 * <ol>
 *   <li>解密 AES 密文 → 解析为 {@link MqttMessageModel.StatusReport}</li>
 *   <li>查询设备基础信息（验证 deviceCode 是否合法）</li>
 *   <li>更新 Redis 实时状态缓存（TTL = 离线阈值 + 1min 缓冲）；{@link #refreshKeepalivePresence} 由 {@link MqttMessageDispatcher} 的 keepaliveExecutor 优先续期，避免业务队列丢弃导致误判离线</li>
 *   <li>将设备加入在线集合（iot:device:online）</li>
 *   <li>同步更新 DB 设备在线状态及位置信息</li>
 *   <li>通过 WebSocket 推送实时状态到前端</li>
 *   <li>异步将历史状态记录写入 DB（不阻塞 MQTT 消费线程）</li>
 * </ol>
 *
 * <p>离线检测机制：
 * Redis Key TTL = DEVICE_OFFLINE_THRESHOLD_MINUTES + 1（分钟）。
 * 若设备停止上报，Key 自然过期；定时任务 {@link org.jeecg.modules.device.scheduler.DeviceSchedulerJob#checkOfflineDevices}
 * 轮询在线设备，发现 Key 不存在则标记为离线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusHandler {

    private final IotDeviceMapper       deviceMapper;
    private final IotDeviceStatusMapper statusMapper;
    private final CommandEncryptService encryptService;
    private final ObjectMapper          objectMapper;
    private final StringRedisTemplate   redisTemplate;
    private final DeviceWebSocketServer webSocketServer;

    private static final long PRESENCE_TTL_MINUTES =
            DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1;

    /**
     * 刷新 keepalive 存活性（Redis Key + 在线集合），供离线判定使用。
     *
     * <p>由 {@link MqttMessageDispatcher} 提交到专用 {@code keepaliveExecutor} 执行，
     * 与 {@link #handle} 使用的 {@code deviceTaskExecutor} 隔离，避免业务队列满丢弃任务导致 Redis TTL 未续期。
     *
     * @param deviceCode 设备编号（从 Topic 提取）
     * @param payload    AES 加密的消息体密文
     */
    public void refreshKeepalivePresence(String deviceCode, String payload) {
        try {
            String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
            touchPresence(deviceCode, decrypted);
        } catch (Exception e) {
            log.warn("[StatusHandler] keepalive 续期解密失败，仅写入占位 Key: deviceCode={}", deviceCode, e);
            touchPresence(deviceCode, "{\"keepalive\":true}");
        }
    }

    private void touchPresence(String deviceCode, String redisValue) {
        redisTemplate.opsForValue().set(
                DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode,
                redisValue,
                PRESENCE_TTL_MINUTES,
                TimeUnit.MINUTES);
        redisTemplate.opsForSet().add(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
    }

    /**
     * 处理设备状态上报消息
     *
     * @param deviceCode 设备编号（从 Topic 中提取）
     * @param payload    AES 加密的消息体密文
     * @throws Exception 解密失败或 JSON 解析失败时抛出
     */
    public void handle(String deviceCode, String payload) throws Exception {
        // 1. 解密 + 解析消息体
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        log.info("[StatusHandler] 解密成功, 设备上报消息体为: {}", decrypted);
        MqttMessageModel.StatusReport r = objectMapper.readValue(decrypted, MqttMessageModel.StatusReport.class);

        // 2. 校验设备是否存在（防止脏数据）
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null) {
            log.warn("[StatusHandler] 未知设备: {}", deviceCode);
            return;
        }

        // 3. 刷新 Redis 存活性（keepaliveExecutor 已续期 TTL，此处再写一次以保证内容与 DB/WS 一致）
        touchPresence(deviceCode, decrypted);

        // 4. 同步更新 DB 中的在线状态和位置（经纬度有值才覆盖，避免用空值覆盖历史有效位置）
        device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
        device.setLastOnlineTime(LocalDateTime.now());
        if (r.getLongitude() != null) { device.setLongitude(r.getLongitude()); }
        if (r.getLatitude()  != null) { device.setLatitude(r.getLatitude()); }
        deviceMapper.updateById(device);

        // 5. WebSocket 实时推送给前端监控页面
        webSocketServer.pushDeviceStatus(deviceCode, decrypted);

        // 6. 异步写入历史状态 DB（使用独立线程池，不占用 MQTT 消费线程）
        // 注：上线 MQ 事件统一由 DeviceOnlineReportHandler 在收到 datacollect/deviceOnline 消息时推送
        persistAsync(device, r, decrypted);
    }

    /**
     * 异步持久化历史状态记录到 DB
     *
     * <p>使用 {@code @Async("deviceTaskExecutor")} 在独立线程池执行，
     * 避免 DB 写入阻塞 MQTT 消费线程，保证消息吞吐量。
     *
     * @param device 设备基础信息
     * @param r      已解析的状态上报对象
     * @param raw    原始解密 JSON 字符串（存入 raw_data 字段备查）
     */
    @Async("deviceTaskExecutor")
    public void persistAsync(IotDevice device, MqttMessageModel.StatusReport r, String raw) {
        IotDeviceStatus s = new IotDeviceStatus();
        s.setDeviceId(device.getId());
        s.setDeviceCode(device.getDeviceCode());
        s.setTemperature(r.getTemperature());
        s.setHumidity(r.getHumidity());
        s.setBatteryLevel(r.getBatteryLevel());
        s.setSignalStrength(r.getSignalStrength());
        s.setLongitude(r.getLongitude());
        s.setLatitude(r.getLatitude());
        s.setRunStatus(r.getRunStatus());
        s.setRawData(raw);
        // reportTime 使用设备端 timestamp（毫秒转 LocalDateTime），反映设备实际采集时间
        s.setReportTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getTimestamp()), ZoneId.systemDefault()));
        // receiveTime 使用平台接收时间，两者差值可用于评估网络延迟
        s.setReceiveTime(LocalDateTime.now());
        statusMapper.insert(s);
    }
}
