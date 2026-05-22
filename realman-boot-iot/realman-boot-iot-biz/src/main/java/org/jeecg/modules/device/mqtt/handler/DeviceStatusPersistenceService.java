package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 设备状态 DB 持久化（独立 Service，保证 {@link Async} 经 Spring 代理生效）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceStatusPersistenceService {

    private final IotDeviceMapper deviceMapper;
    private final IotDeviceStatusMapper statusMapper;
    private final DeviceDbStatusCache dbStatusCache;

    /**
     * 异步写入历史状态表。
     */
    @Async("devicePersistExecutor")
    public void persistHistory(IotDevice device, MqttMessageModel.StatusReport report, String rawJson) {
        try {
            IotDeviceStatus s = new IotDeviceStatus();
            s.setDeviceId(device.getId());
            s.setDeviceCode(device.getDeviceCode());
            s.setTemperature(report.getTemperature());
            s.setHumidity(report.getHumidity());
            s.setBatteryLevel(report.getBatteryLevel());
            s.setSignalStrength(report.getSignalStrength());
            s.setLongitude(report.getLongitude());
            s.setLatitude(report.getLatitude());
            s.setRunStatus(report.getRunStatus());
            s.setRawData(rawJson);
            s.setReportTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(report.getTimestamp()), ZoneId.systemDefault()));
            s.setReceiveTime(LocalDateTime.now());
            statusMapper.insert(s);
        } catch (Exception e) {
            log.warn("[StatusPersist] 历史状态写入失败 deviceCode={}", device.getDeviceCode(), e);
        }
    }

    /**
     * 异步更新设备在线状态（部分字段）。
     */
    @Async("devicePersistExecutor")
    public void updateDeviceOnline(IotDevice update) {
        try {
            deviceMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[StatusPersist] 设备在线状态更新失败 deviceId={}", update.getId(), e);
        }
    }

    /**
     * keepalive 触发的 DB 同步：仅当 DB 当前非 ONLINE 时更新 status + lastOnlineTime。
     * 已在 ONLINE 时只回填本地缓存，不写库。
     */
    @Async("devicePersistExecutor")
    public void promoteOnlineIfOffline(String deviceCode) {
        try {
            IotDevice device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<IotDevice>()
                            .eq(IotDevice::getDeviceCode, deviceCode)
                            .last("LIMIT 1"));
            if (device == null) {
                log.debug("[StatusPersist] 设备不存在，跳过上线同步 deviceCode={}", deviceCode);
                return;
            }
            if (Objects.equals(device.getStatus(), DeviceConstant.DeviceStatus.ONLINE)) {
                dbStatusCache.setStatus(deviceCode, DeviceConstant.DeviceStatus.ONLINE);
                return;
            }
            if (Objects.equals(device.getStatus(), DeviceConstant.DeviceStatus.DISABLED)) {
                log.debug("[StatusPersist] 设备已禁用，跳过上线同步 deviceCode={}", deviceCode);
                return;
            }
            IotDevice update = new IotDevice();
            update.setId(device.getId());
            update.setStatus(DeviceConstant.DeviceStatus.ONLINE);
            update.setLastOnlineTime(LocalDateTime.now());
            deviceMapper.updateById(update);
            dbStatusCache.setStatus(deviceCode, DeviceConstant.DeviceStatus.ONLINE);
            log.info("[StatusPersist] 设备离线态同步为在线 deviceCode={} prevStatus={}",
                    deviceCode, device.getStatus());
        } catch (Exception e) {
            log.warn("[StatusPersist] 离线转在线失败 deviceCode={}", deviceCode, e);
        }
    }

}
