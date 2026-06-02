package org.jeecg.modules.device.scheduler;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.datacollect.producer.DeviceStatusProducer;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeRecordMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeTaskMapper;
import org.jeecg.modules.device.mqtt.handler.DeviceDbStatusCache;
import org.jeecg.modules.device.mqtt.handler.DeviceStatusPersistenceService;
import org.jeecg.modules.device.mqtt.handler.RobotSlaveStatusHandler;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * IoT 设备定时任务
 *
 * <p>包含多个定时任务，均通过 XXL-Job 调度：
 * <ul>
 *   <li>{@link #checkOfflineDevices}：每分钟检测离线设备</li>
 *   <li>{@link #checkOtaTimeout}：每5分钟检测 OTA 升级超时</li>
 *   <li>{@link #checkCommandAckTimeout}：每分钟检测指令 ACK 超时</li>
 *   <li>{@link #compactTodayDeviceStatus}：当天状态每小时压缩</li>
 *   <li>{@link #compactDeviceStatusHistory}：最近7天及更早历史压缩</li>
 *   <li>{@link #flushRobotStatusJob}：每分钟将机器人/主控高频上报状态落库</li>
 * </ul>
 *
 * <p>XXL-Job 配置（在 XXL-Job Admin 后台注册）：
 * <pre>
 *   deviceOfflineCheckJob       Cron: 0 * * * * ?    每分钟执行
 *   otaUpgradeTimeoutCheckJob   Cron: 0 0/5 * * * ?  每5分钟执行
 *   commandAckTimeoutCheckJob   Cron: 0 * * * * ?    每分钟执行
 *   compactTodayDeviceStatusJob Cron: 0 30 * * * ?   每小时第30分钟执行
 *   compactDeviceStatusJob      Cron: 0 20 2 * * ?   每天 02:20 执行
 *   flushRobotStatusJob         Cron: 0 * * * * ?    每分钟执行
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSchedulerJob {

    private final IotDeviceMapper                deviceMapper;
    private final IotDeviceStatusMapper          statusMapper;
    private final IotOtaUpgradeRecordMapper      recordMapper;
    private final IotOtaUpgradeTaskMapper        taskMapper;
    private final StringRedisTemplate            redisTemplate;
    private final DeviceWebSocketServer          webSocketServer;
    private final IWorkOrderService              workOrderService;
    private final IIotDeviceCommandRecordService commandRecordService;
    private final DeviceDbStatusCache dbStatusCache;
    private final DeviceStatusPersistenceService statusPersistenceService;
    /** mqtt.enabled=false 时 Bean 不存在，注入 null，任务方法内做空判断 */
    @Autowired(required = false)
    private RobotSlaveStatusHandler robotSlaveStatusHandler;
    /** darwin.integration.enabled=false 时 Bean 不存在，注入 null，调用前做空判断 */
    @Autowired(required = false)
    private DeviceStatusProducer deviceStatusProducer;

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
        if (onlineDevices.isEmpty()) {
            return;
        }

        // Pipeline 批量检查所有 presence key，一次 Redis 往返替代 N 次 hasKey
        List<String> keys = onlineDevices.stream()
                .map(d -> DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + d.getDeviceCode())
                .toList();
        List<Object> existsResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (String key : keys) {
                conn.exists(key);
            }
            return null;
        });

        int cnt = 0;
        for (int i = 0; i < onlineDevices.size(); i++) {
            if (Boolean.TRUE.equals(existsResults.get(i))) {
                continue;
            }
            IotDevice d = onlineDevices.get(i);
            d.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
            d.setLastOfflineTime(LocalDateTime.now());
            deviceMapper.updateById(d);
            dbStatusCache.setStatus(d.getDeviceCode(), DeviceConstant.DeviceStatus.OFFLINE);
            redisTemplate.opsForSet().remove(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, d.getDeviceCode());
            statusPersistenceService.persistConnectionStatus(
                    d, DeviceConstant.DeviceStatus.STATUS_RECORD_OFFLINE, "heartbeat-timeout", null);
            if (deviceStatusProducer != null
                    && DeviceConstant.DeviceTypeInteger.ROBOT == d.getDeviceType()) {
                String tenant = d.getTenantId() != null ? String.valueOf(d.getTenantId()) : "";
                deviceStatusProducer.sendOfflineEvent(
                        tenant, d.getDeviceCode(), "SLAVE", d.getDeviceModel(), "heartbeat_timeout", MDC.get("traceId"));
            }
            cnt++;
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

        XxlJobHelper.log("[StatusCompact-History] 本次主表压缩/删除 {} 条，归档到历史表 {} 条，历史表清理 {} 条（设备数={}）",totalDeletedMain, totalArchived, historyDeleted, deviceIds.size());
        log.info("[StatusCompact-History] 本次主表压缩/删除 {} 条，归档到历史表 {} 条，历史表清理 {} 条（设备数={}）",
                totalDeletedMain, totalArchived, historyDeleted, deviceIds.size());
    }

    /**
     * 指令 ACK 超时检测任务
     *
     * <p>检测逻辑：单条 UPDATE SQL 批量将所有满足条件的 PENDING 记录标记为 TIMEOUT：
     * <pre>
     *   status = 'PENDING'
     *   AND send_time &lt; NOW() - {@link DeviceConstant.Timeout#COMMAND_ACK_TIMEOUT_SECONDS} 秒
     * </pre>
     *
     * <p>与 ACK 更新的并发安全说明：
     * <ul>
     *   <li>UPDATE 语句带 {@code status = 'PENDING'} 条件，若 ACK 已先到达将记录改为 SUCCESS/FAIL，
     *       此处 UPDATE 命中零行，不会覆盖 ACK 结果</li>
     *   <li>反之若超时任务先执行，随后 ACK 到达，{@link IIotDeviceCommandRecordService#ack} 内
     *       同样带 {@code status = 'PENDING'} 条件，会命中零行并打印 WARN 日志，不产生脏写</li>
     * </ul>
     *
     * <p>XXL-Job Handler Name：{@code commandAckTimeoutCheckJob}，建议 Cron：{@code 0 * * * * ?}
     */
    @XxlJob("commandAckTimeoutCheckJob")
    public void checkCommandAckTimeout() {
        int count = commandRecordService.markTimeout();
        if (count > 0) {
            String msg = "[CommandAckTimeout] 本次标记 " + count + " 条超时未 ACK 的指令记录";
            XxlJobHelper.log(msg);
            log.warn(msg);
        }
    }

    /**
     * 机器人/主控状态高频落库节流任务
     *
     * <p>设备每秒上报状态，平台仅缓存最新一条；本任务每分钟触发一次，
     * 将 {@link RobotSlaveStatusHandler} 中各设备的最新状态统一写入 DB，
     * 每台设备每分钟落一条记录，将 DB 写压力从 ~60次/分钟 降为 1次/分钟。
     *
     * <p>XXL-Job Handler Name：{@code flushRobotStatusJob}，建议 Cron：{@code 0 * * * * ?}
     */
    @XxlJob("flushRobotStatusJob")
    public void flushRobotStatusJob() {
        if (robotSlaveStatusHandler == null) {
            log.debug("[flushRobotStatusJob] MQTT 未启用，跳过");
            return;
        }
        robotSlaveStatusHandler.flushPending();
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
