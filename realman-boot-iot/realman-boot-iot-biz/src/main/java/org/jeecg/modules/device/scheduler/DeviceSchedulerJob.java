package org.jeecg.modules.device.scheduler;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeRecordMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeTaskMapper;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
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
 *   <li>{@link #compactTodayDeviceStatus}：当天状态每小时压缩</li>
 *   <li>{@link #compactDeviceStatusHistory}：最近7天及更早历史压缩</li>
 * </ul>
 *
 * <p>XXL-Job 配置（在 XXL-Job Admin 后台注册）：
 * <pre>
 *   deviceOfflineCheckJob     Cron: 0 * * * * ?    每分钟执行
 *   otaUpgradeTimeoutCheckJob Cron: 0 0/5 * * * ?  每5分钟执行
 *   compactTodayDeviceStatusJob Cron: 0 30 * * * ?  每小时第30分钟执行
 *   compactDeviceStatusJob    Cron: 0 20 2 * * ?   每天 02:20 执行
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSchedulerJob {

    private final IotDeviceMapper           deviceMapper;
    private final IotDeviceStatusMapper     statusMapper;
    private final IotOtaUpgradeRecordMapper recordMapper;
    private final IotOtaUpgradeTaskMapper   taskMapper;
    private final StringRedisTemplate       redisTemplate;
    private final DeviceWebSocketServer webSocketServer;

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

    /**
     * 当天状态压缩任务
     *
     * <p>策略：对“今天内且早于当前小时”的记录进行每小时压缩——每小时仅保留最新一条；
     * 当前小时内的所有上报记录全部保留，避免影响实时分析。
     *
     * <p>XXL-Job Handler Name：{@code compactTodayDeviceStatusJob}，建议 Cron：{@code 0 30 * * * ?}
     */
    @XxlJob("compactTodayDeviceStatusJob")
    public void compactTodayDeviceStatus() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime currentHourStart = now.withMinute(0).withSecond(0).withNano(0);

        List<String> deviceIds = statusMapper.selectAllDeviceIds();
        int totalDeleted = 0;
        for (String deviceId : deviceIds) {
            totalDeleted += statusMapper.deleteTodayHourlyRedundantBeforeHour(deviceId, todayStart, currentHourStart);
        }
        log.info("[StatusCompact-Today] 已压缩今天内早于当前小时的状态记录 {} 条（设备数={}）", totalDeleted, deviceIds.size());
    }

    /**
     * 设备状态历史压缩 & 归档任务
     *
     * <p>策略：
     * <ol>
     *   <li>最近30天（不含当天）：每天仅保留最新一条记录</li>
     *   <li>30天之前：仅保留“窗口开始时间之前”的最后一条记录，其余先归档到 {@code iot_device_status_history} 表，再从主表物理删除</li>
     *   <li>对 {@code iot_device_status_history} 表中超过90天的数据，物理删除</li>
     * </ol>
     *
     * <p>XXL-Job Handler Name：{@code compactDeviceStatusJob}，建议 Cron：{@code 0 20 2 * * ?}
     */
    @XxlJob("compactDeviceStatusJob")
    public void compactDeviceStatusHistory() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        // 最近30天窗口起点（不含更早数据）
        LocalDateTime recentStart = todayStart.minusDays(30);
        // 历史表保留90天，删除更早的数据
        LocalDateTime historyExpireBefore = todayStart.minusDays(90);

        List<String> deviceIds = statusMapper.selectAllDeviceIds();
        int totalDeletedMain = 0;
        int totalArchived = 0;

        for (String deviceId : deviceIds) {
            // 1. 最近30天（不含当天）：每天仅保留最新一条
            totalDeletedMain += statusMapper.deleteRecentDailyRedundant(deviceId, recentStart, todayStart);

            // 2. 30天之前：保留窗口起点之前的最后一条，其余归档+删除
            IotDeviceStatus anchor = statusMapper.selectLatestBefore(deviceId, recentStart);
            String keepId = anchor != null ? anchor.getId() : null;
            // 先备份到历史表
            totalArchived += statusMapper.backupOlderThan(deviceId, recentStart, keepId);
            // 再从主表删除
            totalDeletedMain += statusMapper.deleteOlderThan(deviceId, recentStart, keepId);
        }

        // 3. 清理历史表中超过90天的数据
        int historyDeleted = statusMapper.deleteHistoryOlderThan(historyExpireBefore);

        log.info("[StatusCompact-History] 本次主表压缩/删除 {} 条，归档到历史表 {} 条，历史表清理 {} 条（设备数={}）",
                totalDeletedMain, totalArchived, historyDeleted, deviceIds.size());
    }

    @XxlJob("mockDeviceStatusJob")
    public void mockDeviceStatusJob() {
        List<IotDevice> devices = deviceMapper.selectList(Wrappers.lambdaQuery(IotDevice.class));

        for (IotDevice device : devices) {
            String msg = "{\"type\":\"STATUS\",\"deviceCode\":\"" + device.getDeviceCode() + "\",\"data\":" + JSONUtil.toJsonStr(device) + "}";
            webSocketServer.pushDeviceStatus(device.getDeviceCode(), msg);
        }

        log.info("[mockDeviceStatusJob] 模拟推送数据 {} 条（设备数={}）", devices.size(), devices.size());
    }
}
