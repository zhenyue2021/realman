package org.jeecg.modules.device.service;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.mapper.IotSlamCommandRecordMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 导航路径实时监控（集群安全）
 *
 * <p>在单点导航收到 PARTIAL 响应后启动，周期性 MQTT 查询 {@code GetCurrentPlannedPath}；
 * 终态 ACK 到达后 {@link #stopMonitor} 停止。集群协调见 {@link ClusterScheduledMonitor}。
 */
@Slf4j
@Service
public class NavigationPathMonitorService extends ClusterScheduledMonitor {

    /** Redis Pub/Sub 停止信号频道前缀（供 RedisPendingListenerConfig 注册） */
    public static final String STOP_CHANNEL_PREFIX = "iot:nav:monitor:stop:";

    private static final String ACTIVE_KEY_PREFIX = "iot:nav:monitor:active:";
    private static final long SAFETY_TTL_SECONDS = 1800L;
    private static final int POLL_INTERVAL_MS = 200;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "nav-path-monitor");
                t.setDaemon(true);
                return t;
            });

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IotSlamCommandRecordMapper commandRecordMapper;
    private final TransactionTemplate transactionTemplate;

    public NavigationPathMonitorService(StringRedisTemplate redisTemplate,
                                        MqttPublisher mqttPublisher,
                                        ObjectMapper objectMapper,
                                        IotSlamCommandRecordMapper commandRecordMapper,
                                        TransactionTemplate transactionTemplate) {
        super(redisTemplate, STOP_CHANNEL_PREFIX, "NavMonitor", SCHEDULER);
        this.mqttPublisher = mqttPublisher;
        this.objectMapper = objectMapper;
        this.commandRecordMapper = commandRecordMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 若该机器人尚无活跃路径监控则启动（幂等，Redis SET NX 防多节点重复）。
     */
    public void startMonitorIfAbsent(String robotCode, String masterCode, String taskId) {
        log.debug("[NavMonitor] 启动路径监控: robotCode={}", robotCode);
        if (hasLocalTask(robotCode)) {
            log.debug("[NavMonitor] 本节点监控已存在，跳过: robotCode={}", robotCode);
            return;
        }
        String activeKey = ACTIVE_KEY_PREFIX + robotCode;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(activeKey, masterCode, SAFETY_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[NavMonitor] Redis 标记已存在（其他节点已启动），跳过: robotCode={}", robotCode);
            return;
        }
        scheduleFixedDelay(
                robotCode,
                () -> poll(robotCode, masterCode, taskId),
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        log.info("[NavMonitor] 路径监控已启动: robotCode={}, intervalMs={}", robotCode, POLL_INTERVAL_MS);
    }

    /** 停止指定机器人的路径监控（广播至所有 Pod）。 */
    public void stopMonitor(String robotCode) {
        redisTemplate.delete(ACTIVE_KEY_PREFIX + robotCode);
        broadcastStop(robotCode);
    }

    private void poll(String robotCode, String masterCode, String taskId) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_KEY_PREFIX + robotCode))) {
            log.info("[NavMonitor] Redis 标记已消失，自动停止: robotCode={}", robotCode);
            cancelLocal(robotCode);
            return;
        }
        sendGetCurrentPlannedPath(robotCode, masterCode, taskId);
    }

    private void sendGetCurrentPlannedPath(String robotCode, String masterCode, String taskId) {
        try {
            String commandId = "req_" + DeviceConstant.SlamFunction.GET_CURRENT_PLANNED_PATH
                    + "_" + IdUtil.getSnowflakeNextId();

            IotSlamCommandRecord record = new IotSlamCommandRecord();
            record.setRobotCode(robotCode);
            record.setMasterCode(masterCode);
            record.setCommandId(commandId);
            record.setFunctionName(DeviceConstant.SlamFunction.GET_CURRENT_PLANNED_PATH);
            record.setStatus(DeviceConstant.SlamCommandStatus.PENDING);
            record.setSendTime(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(s -> commandRecordMapper.insert(record));

            MqttMessageModel.SlamRequest request = MqttMessageModel.SlamRequest.builder()
                    .commandId(commandId)
                    .function(DeviceConstant.SlamFunction.GET_CURRENT_PLANNED_PATH)
                    .params(Map.of("task_id", taskId))
                    .build();
            String topic = String.format(DeviceConstant.MqttTopic.SLAM_REQUEST, robotCode);
            mqttPublisher.publishToDevice(robotCode, topic,
                    objectMapper.writeValueAsString(request), MqttConstant.MQTT_QOS.QOS_1);

            log.debug("[NavMonitor] 路径查询已发送: robotCode={}, commandId={}", robotCode, commandId);
        } catch (Exception e) {
            log.warn("[NavMonitor] 路径查询发送失败: robotCode={}", robotCode, e);
        }
    }
}
