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

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.StatusReport r = objectMapper.readValue(decrypted, MqttMessageModel.StatusReport.class);

        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null) { log.warn("[StatusHandler] 未知设备: {}", deviceCode); return; }

        // Redis缓存实时状态（TTL = 离线判定阈值 + 1min 缓冲）
        redisTemplate.opsForValue().set(
                DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode, decrypted,
                DeviceConstant.Timeout.DEVICE_OFFLINE_THRESHOLD_MINUTES + 1, TimeUnit.MINUTES);
        redisTemplate.opsForSet().add(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);

        // 更新设备在线
        device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
        device.setLastOnlineTime(LocalDateTime.now());
        if (r.getLongitude() != null) device.setLongitude(r.getLongitude());
        if (r.getLatitude()  != null) device.setLatitude(r.getLatitude());
        deviceMapper.updateById(device);

        // WebSocket实时推送
        webSocketServer.pushDeviceStatus(deviceCode, decrypted);

        // 异步写DB历史记录
        persistAsync(device, r, decrypted);
    }

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
        s.setReportTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getTimestamp()), ZoneId.systemDefault()));
        s.setReceiveTime(LocalDateTime.now());
        statusMapper.insert(s);
    }
}
