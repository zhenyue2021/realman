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

    /** keepalive / 对账 将 DB 提升为 ONLINE 的结果 */
    public enum PromoteOnlineResult {
        ALREADY_ONLINE, PROMOTED, SKIPPED_DISABLED, NOT_FOUND, FAILED
    }

    /**
     * 异步包装：委托 {@link #promoteOnlineIfOffline(String)}，供历史调用方兼容。
     */
    @Async("devicePersistExecutor")
    public void promoteOnlineIfOfflineAsync(String deviceCode) {
        promoteOnlineIfOffline(deviceCode);
    }

    /**
     * 同步将 DB 非 ONLINE 设备提升为 ONLINE（keepalive / EMQX 对账使用）。
     */
    public PromoteOnlineResult promoteOnlineIfOffline(String deviceCode) {
        try {
            IotDevice device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<IotDevice>()
                            .eq(IotDevice::getDeviceCode, deviceCode)
                            .eq(IotDevice::getDelFlag, 0)
                            .last("LIMIT 1"));
            if (device == null) {
                log.warn("[StatusPersist] 设备不存在，跳过上线同步 deviceCode={}", deviceCode);
                return PromoteOnlineResult.NOT_FOUND;
            }
            if (Objects.equals(device.getStatus(), DeviceConstant.DeviceStatus.ONLINE)) {
                dbStatusCache.setStatus(deviceCode, DeviceConstant.DeviceStatus.ONLINE);
                return PromoteOnlineResult.ALREADY_ONLINE;
            }
            if (Objects.equals(device.getStatus(), DeviceConstant.DeviceStatus.DISABLED)) {
                log.info("[StatusPersist] 设备已禁用，跳过上线同步 deviceCode={}", deviceCode);
                return PromoteOnlineResult.SKIPPED_DISABLED;
            }
            Integer prevStatus = device.getStatus();
            IotDevice update = new IotDevice();
            update.setId(device.getId());
            update.setStatus(DeviceConstant.DeviceStatus.ONLINE);
            update.setLastOnlineTime(LocalDateTime.now());
            int rows = deviceMapper.updateById(update);
            if (rows > 0) {
                dbStatusCache.setStatus(deviceCode, DeviceConstant.DeviceStatus.ONLINE);
                log.info("[StatusPersist] 设备同步为在线 deviceCode={} prevStatus={}",
                        deviceCode, prevStatus);
                return PromoteOnlineResult.PROMOTED;
            }
            log.warn("[StatusPersist] 设备上线写库未生效 deviceCode={} prevStatus={}", deviceCode, prevStatus);
            return PromoteOnlineResult.FAILED;
        } catch (Exception e) {
            log.warn("[StatusPersist] 离线转在线失败 deviceCode={}", deviceCode, e);
            return PromoteOnlineResult.FAILED;
        }
    }

    /**
     * @return true 表示已是 ONLINE 或本次写库成功；false 表示设备不存在、已禁用或写库失败
     * @deprecated 请使用 {@link #promoteOnlineIfOffline(String)}
     */
    @Deprecated
    public boolean promoteOnlineIfOfflineSync(String deviceCode) {
        PromoteOnlineResult result = promoteOnlineIfOffline(deviceCode);
        return result == PromoteOnlineResult.ALREADY_ONLINE || result == PromoteOnlineResult.PROMOTED;
    }

}
