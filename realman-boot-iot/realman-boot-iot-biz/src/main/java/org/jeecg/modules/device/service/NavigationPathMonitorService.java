package org.jeecg.modules.device.service;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.mapper.IotSlamCommandRecordMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 导航路径实时监控服务（集群安全）
 *
 * <p>在 {@code ExecuteSinglePointNavigation} 收到首次成功响应（PARTIAL）后启动，
 * 周期性向机器人发送 {@code GetCurrentPlannedPath} 指令，结果由
 * {@link IIotSlamCommandService#handleAck} 统一推送 WebSocket；
 * 当导航到达终态（COMPLETED / FAILED）后停止。
 *
 * <h3>集群安全设计</h3>
 * <p>本服务实现 {@link MessageListener}，通过与项目一致的 Redis Pub/Sub 机制解决集群问题：
 * <pre>
 *   场景：Node A 启动监控，Node B 收到终态 ACK
 *
 *   Node B.stopMonitor(robotCode)
 *     → 删除 Redis 活跃标记（安全 TTL 兜底）
 *     → redisTemplate.convertAndSend(STOP_CHANNEL + robotCode, "stop")
 *
 *   所有节点（含 Node A）收到 Redis 消息
 *     → onMessage() → 取消本节点的 ScheduledFuture（若有）
 * </pre>
 *
 * <p>Redis 活跃标记（{@value #ACTIVE_KEY_PREFIX}{robotCode}）：
 * <ul>
 *   <li>TTL = {@value #SAFETY_TTL_SECONDS}s，防止进程异常时监控泄漏</li>
 *   <li>每次轮询检查 Key 是否存在；若已被删除则自行停止</li>
 *   <li>SET NX 防止（极端情况下）多节点重复启动</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavigationPathMonitorService implements MessageListener {

    /** Redis Pub/Sub 停止信号频道前缀（供 RedisPendingListenerConfig 注册） */
    public static final String STOP_CHANNEL_PREFIX = "iot:nav:monitor:stop:";

    /** Redis 活跃标记 Key 前缀；Value = masterCode */
    private static final String ACTIVE_KEY_PREFIX = "iot:nav:monitor:active:";

    /** 活跃标记 TTL：30 分钟安全兜底（正常情况下导航完成后即被删除） */
    private static final long SAFETY_TTL_SECONDS = 1800L;

    /** 路径查询轮询间隔（毫秒） */
    private static final int POLL_INTERVAL_SECONDS = 200;

    /** 共享调度线程池，daemon 线程 */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "nav-path-monitor");
                t.setDaemon(true);
                return t;
            });

    /** 本节点活跃监控任务：Key = robotCode，Value = 可取消的调度 Future */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeMonitors = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IotSlamCommandRecordMapper commandRecordMapper;
    private final TransactionTemplate transactionTemplate;

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 若该机器人尚无活跃路径监控则启动（幂等）。
     *
     * <p>使用 Redis SET NX 作为分布式互斥锁，防止极端情况下多节点重复启动。
     *
     * @param robotCode  机器人设备编码
     * @param masterCode 主控设备编码（写入 DB 记录，供 handleAck 推 WebSocket）
     * @param taskId      监控的导航指令 ID
     */
    public void startMonitorIfAbsent(String robotCode, String masterCode, String taskId) {
        // 1. 本节点内存快检（无 Redis 往返，大多数调用在此短路）
        if (activeMonitors.containsKey(robotCode)) {
            log.debug("[NavMonitor] 本节点监控已存在，跳过: robotCode={}", robotCode);
            return;
        }
        // 2. Redis SET NX：分布式互斥，防多节点重复启动
        String activeKey = ACTIVE_KEY_PREFIX + robotCode;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(activeKey, masterCode, SAFETY_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[NavMonitor] Redis 标记已存在（其他节点已启动），跳过: robotCode={}", robotCode);
            return;
        }
        // 3. 调度本地定时任务
        // 使用 scheduleWithFixedDelay（上次执行完成后再等待间隔），避免 DB+MQTT 耗时超过间隔时任务堆积
        ScheduledFuture<?> future = SCHEDULER.scheduleWithFixedDelay(
                () -> poll(robotCode, masterCode, taskId),
                POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.MILLISECONDS
        );
        activeMonitors.put(robotCode, future);
        log.info("[NavMonitor] 路径监控已启动: robotCode={}, interval={}s", robotCode, POLL_INTERVAL_SECONDS);
    }

    /**
     * 停止指定机器人的路径监控（导航终态时调用）。
     *
     * <p>通过 Redis Pub/Sub 广播停止信号，所有节点的 {@link #onMessage} 均会收到并
     * 取消本地 ScheduledFuture，保证集群中无论哪个节点持有监控都能被停止。
     *
     * @param robotCode 机器人设备编码
     */
    public void stopMonitor(String robotCode) {
        // 删除 Redis 活跃标记（轮询任务的自检也会因此停止）
        redisTemplate.delete(ACTIVE_KEY_PREFIX + robotCode);
        // 广播停止信号到所有节点（含本节点）
        try {
            redisTemplate.convertAndSend(STOP_CHANNEL_PREFIX + robotCode, "stop");
        } catch (Exception e) {
            // Redis 不可用时降级为本地取消
            log.warn("[NavMonitor] Redis 发布停止信号失败，降级本地停止: robotCode={}", robotCode, e);
            cancelLocal(robotCode);
        }
    }

    // -------------------------------------------------------------------------
    // Redis Pub/Sub 回调（MessageListener）
    // -------------------------------------------------------------------------

    /**
     * 收到停止信号（任意节点调用 stopMonitor 后广播）：取消本节点的调度任务。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String robotCode = channel.substring(STOP_CHANNEL_PREFIX.length());
        cancelLocal(robotCode);
    }

    // -------------------------------------------------------------------------
    // 内部方法
    // -------------------------------------------------------------------------

    /**
     * 定时轮询任务：自检 Redis 活跃标记，发送一次 GetCurrentPlannedPath 查询。
     */
    private void poll(String robotCode, String masterCode, String taskId) {
        // 自检：Redis 活跃标记已被删除（stopMonitor 或 TTL 过期）则自行停止
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_KEY_PREFIX + robotCode))) {
            log.info("[NavMonitor] Redis 标记已消失，自动停止: robotCode={}", robotCode);
            cancelLocal(robotCode);
            return;
        }
        sendGetCurrentPlannedPath(robotCode, masterCode, taskId);
    }

    /**
     * 取消本节点的 ScheduledFuture（不操作 Redis）。
     */
    private void cancelLocal(String robotCode) {
        ScheduledFuture<?> future = activeMonitors.remove(robotCode);
        if (future != null) {
            future.cancel(false);
            log.info("[NavMonitor] 本节点路径监控已取消: robotCode={}", robotCode);
        }
    }

    /**
     * 向机器人发送一次 GetCurrentPlannedPath 查询（fire-and-forget）。
     *
     * <ol>
     *   <li>预写 DB 记录（PENDING），供 handleAck 定位并推 WebSocket。</li>
     *   <li>发布 MQTT 下行报文。</li>
     * </ol>
     */
    private void sendGetCurrentPlannedPath(String robotCode, String masterCode, String taskId) {
        try {
            String commandId = "req_" + DeviceConstant.SlamFunction.GET_CURRENT_PLANNED_PATH + "_" + IdUtil.getSnowflakeNextId();

            // 预写 DB 记录（独立事务立即提交）
            IotSlamCommandRecord record = new IotSlamCommandRecord();
            record.setRobotCode(robotCode);
            record.setMasterCode(masterCode);
            record.setCommandId(commandId);
            record.setFunctionName(DeviceConstant.SlamFunction.GET_CURRENT_PLANNED_PATH);
            record.setStatus(DeviceConstant.SlamCommandStatus.PENDING);
            record.setSendTime(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(s -> commandRecordMapper.insert(record));

            // 发送 MQTT
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
