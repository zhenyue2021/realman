package org.jeecg.modules.device.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

/**
 * IoT 设备定时任务
 *
 * <p>包含两个定时任务，均通过 XXL-Job 调度：
 * <ul>
 *   <li>{@link #checkOfflineDevices}：每分钟检测离线设备</li>
 *   <li>{@link #checkOtaTimeout}：每5分钟检测 OTA 升级超时</li>
 * </ul>
 *
 * <p>XXL-Job 配置（在 XXL-Job Admin 后台注册）：
 * <pre>
 *   deviceOfflineCheckJob     Cron: 0 * * * * ?    每分钟执行
 *   otaUpgradeTimeoutCheckJob Cron: 0 0/5 * * * ?  每5分钟执行
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSchedulerJob {

    private final IotDeviceMapper           deviceMapper;
    private final IotOtaUpgradeRecordMapper recordMapper;
    private final IotOtaUpgradeTaskMapper   taskMapper;
    private final StringRedisTemplate       redisTemplate;

    /**
     * 设备离线检测任务
     *
     * <p>检测逻辑：查询 DB 中所有在线状态（status=ONLINE）的设备，
     * 检查对应 Redis Key（{@link DeviceConstant.RedisKey#DEVICE_STATUS_PREFIX}{deviceCode}）是否存在。
     *
     * <p>Redis Key 在每次设备上报状态时刷新 TTL = 离线阈值 + 1min（即 6 分钟）。
     * 若 Key 已过期，说明设备超过阈值时间未上报状态，判定为离线：
     * <ol>
     *   <li>更新 DB 设备状态为 OFFLINE，记录下线时间</li>
     *   <li>从在线集合（iot:device:online）中移除</li>
     * </ol>
     *
     * <p>注意：此任务是离线状态的兜底机制，设备正常断线会通过 $SYS 事件实时更新，
     * 此任务主要处理异常断线（如断网后 EMQX 未能及时发送 $SYS 事件）的情况。
     *
     * <p>XXL-Job Handler Name：{@code deviceOfflineCheckJob}，建议 Cron：{@code 0 * * * * ?}
     */
    @XxlJob("deviceOfflineCheckJob")
    public void checkOfflineDevices() {
        // 查询当前 DB 标记为在线的所有设备
        List<IotDevice> onlineDevices = deviceMapper.selectList(
                new LambdaQueryWrapper<IotDevice>()
                        .eq(IotDevice::getStatus, DeviceConstant.DeviceStatus.ONLINE));
        int cnt = 0;
        for (IotDevice d : onlineDevices) {
            String key = DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + d.getDeviceCode();
            // Redis Key 不存在说明设备超过 DEVICE_OFFLINE_THRESHOLD_MINUTES 分钟未上报状态
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
     * OTA 升级超时检测任务
     *
     * <p>检测逻辑：查询所有处于升级中间状态（NOTIFIED/CONFIRMED/DOWNLOADING）且
     * 通知时间早于超时阈值的升级记录，将其标记为 TIMEOUT。
     *
     * <p>超时判定：notifyTime < NOW - {@link DeviceConstant.Timeout#OTA_UPGRADE_TIMEOUT_MINUTES} 分钟
     *
     * <p>处理流程：
     * <ol>
     *   <li>查询超时的升级记录列表</li>
     *   <li>逐条标记为 TIMEOUT，设置失败原因和结束时间</li>
     *   <li>刷新对应任务的统计数据（失败数+1）</li>
     * </ol>
     *
     * <p>XXL-Job Handler Name：{@code otaUpgradeTimeoutCheckJob}，建议 Cron：{@code 0 0/5 * * * ?}
     */
    @XxlJob("otaUpgradeTimeoutCheckJob")
    public void checkOtaTimeout() {
        // 计算超时阈值时间点
        LocalDateTime threshold = LocalDateTime.now()
                .minusMinutes(DeviceConstant.Timeout.OTA_UPGRADE_TIMEOUT_MINUTES);

        // 查询所有超时的升级中记录（NOTIFIED/CONFIRMED/DOWNLOADING 状态 + notifyTime 超过阈值）
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
            // 刷新任务统计（失败数 +1，升级中数 -1）
            taskMapper.refreshTaskStatistics(r.getTaskId());
        }
        if (!timeoutList.isEmpty()) log.warn("[OtaTimeout] 标记{}条超时升级记录", timeoutList.size());
    }
}
