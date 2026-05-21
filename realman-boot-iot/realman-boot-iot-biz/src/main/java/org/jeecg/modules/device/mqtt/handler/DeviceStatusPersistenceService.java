package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 设备状态 DB 持久化（独立 Service，保证 {@link Async} 经 Spring 代理生效）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceStatusPersistenceService {

    private final IotDeviceMapper deviceMapper;
    private final IotDeviceStatusMapper statusMapper;

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
     * 异步更新设备在线状态与位置（路由线程只做条件判断，实际 DB 写在此执行）。
     */
    @Async("devicePersistExecutor")
    public void updateDeviceOnline(IotDevice update) {
        try {
            deviceMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[StatusPersist] 设备在线状态更新失败 deviceId={}", update.getId(), e);
        }
    }

}
