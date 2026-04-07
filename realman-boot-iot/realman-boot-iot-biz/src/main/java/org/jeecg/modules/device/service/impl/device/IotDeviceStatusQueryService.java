package org.jeecg.modules.device.service.impl.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.entity.IotDeviceOperationLog;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备状态查询：监控状态聚合（Redis 优先、DB 降级）、详情、批量在线状态。
 */
@Service
@RequiredArgsConstructor
public class IotDeviceStatusQueryService {

    private final IotDeviceSupport deviceSupport;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceStatusMapper statusMapper;
    private final IotDeviceConfigMapper configMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;

    public Map<String, Object> getDeviceMonitorStatus(String deviceId) {
        IotDevice device = deviceSupport.require(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("deviceCode", device.getDeviceCode());
        result.put("status", device.getStatus());
        result.put("lastOnlineTime", device.getLastOnlineTime());

        String cached = redisTemplate.opsForValue().get(
                DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + device.getDeviceCode());
        if (cached != null) {
            try {
                Map<String, Object> cachedMap = objectMapper.readValue(
                        cached, new TypeReference<Map<String, Object>>() {});
                result.putAll(cachedMap);
                result.put("dataSource", "realtime");
            } catch (Exception ignored) {
                // ignore parse errors
            }
        } else {
            IotDeviceStatus s = statusMapper.selectOne(new LambdaQueryWrapper<IotDeviceStatus>()
                    .eq(IotDeviceStatus::getDeviceId, deviceId)
                    .orderByDesc(IotDeviceStatus::getReceiveTime).last("LIMIT 1"));
            if (s != null) {
                result.put("temperature", s.getTemperature());
                result.put("humidity", s.getHumidity());
                result.put("batteryLevel", s.getBatteryLevel());
                result.put("signalStrength", s.getSignalStrength());
                result.put("longitude", s.getLongitude());
                result.put("latitude", s.getLatitude());
                result.put("runStatus", s.getRunStatus());
                result.put("reportTime", s.getReportTime());
                result.put("dataSource", "database");
            }
        }
        return result;
    }

    public DeviceDetailVO getDeviceDetail(String deviceId) {
        IotDevice device = deviceSupport.require(deviceId);

        boolean online = Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, device.getDeviceCode()));

        Map<String, Object> realtime = getDeviceMonitorStatus(deviceId);

        IotDeviceStatus latest = statusMapper.selectOne(new LambdaQueryWrapper<IotDeviceStatus>()
                .eq(IotDeviceStatus::getDeviceId, deviceId)
                .orderByDesc(IotDeviceStatus::getReceiveTime).last("LIMIT 1"));

        LocalDateTime heartbeat = null;
        if (realtime != null) {
            Object ts = realtime.get("timestamp");
            if (ts instanceof Number n) {
                heartbeat = LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
            } else if (ts instanceof String s) {
                try {
                    long ms = Long.parseLong(s);
                    heartbeat = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        if (heartbeat == null && latest != null) {
            heartbeat = latest.getReportTime() != null ? latest.getReportTime() : latest.getReceiveTime();
        }

        List<IotDeviceConfig> deviceConfigs = configMapper.selectList(
                new LambdaQueryWrapper<IotDeviceConfig>()
                        .eq(IotDeviceConfig::getDeviceId, deviceId)
                        .orderByAsc(IotDeviceConfig::getConfigKey));
        deviceConfigs.forEach(cfg -> {
            switch (cfg.getConfigKey()) {
                case "arm_level" -> cfg.setArmLevelConfigType(cfg.getConfigType());
                case "gripper_level" -> cfg.setGripperLevelConfigType(cfg.getConfigType());
                case "move_speed_level" -> cfg.setMoveSpeedLevelConfigType(cfg.getConfigType());
                case "lift_speed_level" -> cfg.setLiftSpeedLevelConfigType(cfg.getConfigType());
                default -> { /* 其他 configKey 无需处理 */ }
            }
        });

        List<IotDeviceOperationLog> recentLogs = logService
                .queryLogPage(new Page<>(1, 20), deviceId, null, null, null)
                .getRecords();

        DeviceDetailVO vo = new DeviceDetailVO();
        vo.setDevice(device);
        vo.setOnline(online);
        vo.setRealtimeStatus(realtime);
        vo.setDeviceConfigs(deviceConfigs);
        vo.setLatestStatus(latest);
        vo.setLastHeartbeatTime(heartbeat);
        vo.setRecentLogs(recentLogs);
        return vo;
    }

    public List<Map<String, Object>> batchGetOnlineStatus(List<String> deviceIds) {
        return deviceIds.stream().map(id -> {
            IotDevice d = deviceMapper.selectById(id);
            Map<String, Object> item = new LinkedHashMap<>();
            if (d != null) {
                boolean online = Boolean.TRUE.equals(redisTemplate.opsForSet()
                        .isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, d.getDeviceCode()));
                item.put("deviceId", id);
                item.put("deviceCode", d.getDeviceCode());
                item.put("status", d.getStatus());
                item.put("onlineInRedis", online);
                item.put("lastOnlineTime", d.getLastOnlineTime());
            }
            return item;
        }).collect(Collectors.toList());
    }
}
