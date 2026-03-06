package org.jeecg.modules.device.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeRecordMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeTaskMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSchedulerJob {

    private final IotDeviceMapper           deviceMapper;
    private final IotOtaUpgradeRecordMapper recordMapper;
    private final IotOtaUpgradeTaskMapper   taskMapper;
    private final StringRedisTemplate       redisTemplate;

    /**
     * 离线检测：每分钟扫描超过阈值无状态上报的设备，标记为离线
     * XXL-Job Cron: 0 * * * * ?
     */
    @XxlJob("deviceOfflineCheckJob")
    public void checkOfflineDevices() {
        List<IotDevice> onlineDevices = deviceMapper.selectList(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getStatus, DeviceConstant.DeviceStatus.ONLINE));
        int cnt = 0;
        for (IotDevice d : onlineDevices) {
            String key = DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + d.getDeviceCode();
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                d.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
                d.setLastOfflineTime(LocalDateTime.now());
                deviceMapper.updateById(d);
                redisTemplate.opsForSet().remove(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, d.getDeviceCode());
                cnt++;
            }
        }
        if (cnt > 0) log.info("[OfflineCheck] 本次检测标记{}台设备为离线", cnt);
    }

    /**
     * OTA超时检测：每5分钟检查升级中超过30分钟的记录，标记为TIMEOUT
     * XXL-Job Cron: 0 0/5 * * * ?
     */
    @XxlJob("otaUpgradeTimeoutCheckJob")
    public void checkOtaTimeout() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusMinutes(DeviceConstant.Timeout.OTA_UPGRADE_TIMEOUT_MINUTES);
        List<IotOtaUpgradeRecord> timeoutList = recordMapper.selectList(
                new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .in(IotOtaUpgradeRecord::getUpgradeStatus,
                                DeviceConstant.OtaUpgradeStatus.NOTIFIED,
                                DeviceConstant.OtaUpgradeStatus.CONFIRMED,
                                DeviceConstant.OtaUpgradeStatus.DOWNLOADING)
                        .lt(IotOtaUpgradeRecord::getNotifyTime, threshold));
        for (IotOtaUpgradeRecord r : timeoutList) {
            r.setUpgradeStatus(DeviceConstant.OtaUpgradeStatus.TIMEOUT);
            r.setFailReason("升级超时（超过" + DeviceConstant.Timeout.OTA_UPGRADE_TIMEOUT_MINUTES + "分钟）");
            r.setFinishTime(LocalDateTime.now());
            recordMapper.updateById(r);
            taskMapper.refreshTaskStatistics(r.getTaskId());
        }
        if (!timeoutList.isEmpty()) log.warn("[OtaTimeout] 标记{}条超时升级记录", timeoutList.size());
    }
}
