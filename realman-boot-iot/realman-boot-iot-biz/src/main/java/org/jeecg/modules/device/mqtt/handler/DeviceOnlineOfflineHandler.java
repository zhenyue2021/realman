package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.PendingSyncService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 处理EMQX $SYS上下线事件
 * 设备身份在MQTT连接层已验证，上线事件即代表设备已通过鉴权
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceOnlineOfflineHandler {

    private final IotDeviceMapper deviceMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeviceWebSocketServer webSocketServer;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;
    private final PendingSyncService pendingSyncService;

    public void handleOnline(String topic, String payload) {
        try {
            String deviceCode = extractClientId(topic, payload);
            if (deviceCode == null || deviceCode.startsWith("iot-platform")) return;
            IotDevice device = findDevice(deviceCode);
            if (device == null) return;
            device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
            device.setLastOnlineTime(LocalDateTime.now());
            deviceMapper.updateById(device);
            redisTemplate.opsForSet().add(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            webSocketServer.pushDeviceOnlineStatus(deviceCode, true);
            log.info("[Online] 设备[{}]上线", deviceCode);
            logService.recordLog(device.getId(), deviceCode, DeviceConstant.OperationType.DEVICE_ONLINE,
                    "设备MQTT连接建立，上线", null, DeviceConstant.OperationSource.DEVICE,
                    "SUCCESS", null, null, null);
        } catch (Exception e) {
            log.error("[Online] 处理异常", e);
        }
    }

    public void handleOffline(String topic, String payload) {
        String deviceCode = extractClientId(topic, payload);
        if (deviceCode == null || deviceCode.startsWith("iot-platform")) return;
        IotDevice device = findDevice(deviceCode);
        if (device == null) return;
        try {
            device.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
            device.setLastOfflineTime(LocalDateTime.now());
            deviceMapper.updateById(device);
            redisTemplate.opsForSet().remove(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            redisTemplate.delete(DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode);
            webSocketServer.pushDeviceOnlineStatus(deviceCode, false);
            String reason = extractField(payload, "reason");
            log.info("[Offline] 设备[{}]下线, reason={}", deviceCode, reason);
            logService.recordLog(device.getId(), deviceCode, DeviceConstant.OperationType.DEVICE_OFFLINE,
                    "设备MQTT连接断开，离线，原因: " + reason, null,
                    DeviceConstant.OperationSource.DEVICE, "SUCCESS", null, null, null);
        } catch (Exception e) {
            log.error("[Offline] 处理异常", e);
        }
        // ★ TODO 设备上线后，检查是否有待同步的离线消息
//        pendingSyncService.flushPendingMessages(deviceCode);
    }

    private String extractClientId(String topic, String payload) {
        String[] parts = topic.split("/");
        for (int i = 0; i < parts.length; i++)
            if ("clients".equals(parts[i]) && i + 1 < parts.length) return parts[i + 1];
        return extractField(payload, "clientid");
    }

    @SuppressWarnings("unchecked")
    private String extractField(String payload, String field) {
        try {
            return String.valueOf(((Map<?, ?>) objectMapper.readValue(payload, Map.class)).get(field));
        } catch (Exception e) {
            return "unknown";
        }
    }

    private IotDevice findDevice(String deviceCode) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
    }
}
