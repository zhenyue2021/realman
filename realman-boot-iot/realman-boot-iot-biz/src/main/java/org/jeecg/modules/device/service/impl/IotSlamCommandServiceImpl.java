package org.jeecg.modules.device.service.impl;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.mapper.IotSlamCommandRecordMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IIotSlamCommandService;
import org.jeecg.modules.device.service.SlamCommandPendingService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotSlamCommandServiceImpl extends ServiceImpl<IotSlamCommandRecordMapper, IotSlamCommandRecord>
        implements IIotSlamCommandService {

    /** 等待设备首次 ACK 的超时时间（秒） */
    private static final long ACK_WAIT_TIMEOUT_SECONDS = 5L;

    /** SLAM 状态 Redis TTL（秒），5 分钟 */
    private static final long SLAM_STATES_TTL = 300L;

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final RedisUtil redisUtil;
    /** 与 {@link MasterLoginResolveServiceImpl} 写入遥操关系时一致，必须用字符串方式读写 */
    private final StringRedisTemplate stringRedisTemplate;
    private final SlamCommandPendingService pendingService;
    private final DeviceWebSocketServer webSocketServer;
    private final TransactionTemplate transactionTemplate;

    @Override
    public IotSlamCommandRecord sendCommand(String masterCode, String robotCode, String function, Map<String, Object> params) {
        String commandId = "req_" + function + "_" + IdUtil.getSnowflakeNextId();

        // 构建下行报文
        MqttMessageModel.SlamRequest request = MqttMessageModel.SlamRequest.builder()
                .requestId(commandId)
                .function(function)
                .params(params)
                .build();

        // 在独立事务中保存记录并立即提交，确保 handleAck 线程能读到该记录。
        // 不可使用方法级 @Transactional：若事务未提交就阻塞等待 ACK，handleAck 会因
        // 读不到记录而走异常分支，导致状态永远停在 PENDING。
        IotSlamCommandRecord record = new IotSlamCommandRecord();
        record.setMasterCode(masterCode);
        record.setRobotCode(robotCode);
        record.setCommandId(commandId);
        record.setFunctionName(function);
        record.setParamsJson(params != null ? JSON.toJSONString(params) : null);
        record.setStatus(DeviceConstant.SlamCommandStatus.PENDING);
        record.setSendTime(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(s -> this.save(record));

        // 注册 Future（在发送 MQTT 前注册，防止 ACK 先于 Future 注册到达）
        CompletableFuture<MqttMessageModel.SlamAck> future = pendingService.register(commandId);

        // 发送 MQTT
        try {
            String topic = String.format(DeviceConstant.MqttTopic.SLAM_REQUEST, robotCode);
            String payload = objectMapper.writeValueAsString(request);
            mqttPublisher.publishToDevice(robotCode, topic, payload, MqttConstant.MQTT_QOS.QOS_1);
            log.info("[SlamCommand] 指令已发送: robotCode={}, commandId={}, function={}", robotCode, commandId, function);
        } catch (Exception e) {
            pendingService.completeExceptionally(commandId, e);
            log.error("[SlamCommand] MQTT 发送失败: robotCode={}, commandId={}", robotCode, commandId, e);
            record.setStatus(DeviceConstant.SlamCommandStatus.FAILED);
            record.setAckMessage("MQTT 发送失败: " + e.getMessage());
            record.setCompleteTime(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(s -> this.updateById(record));
            return record;
        }

        // 等待设备首次 ACK（设备收到请求后会立即响应第一次结果）
        // 若 total > 1，后续响应会在 this.handleAck 中继续更新 DB，此处只等第一次即可返回给调用方
        try {
            future.get(ACK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("[SlamCommand] 收到首次 ACK: robotCode={}, commandId={}", robotCode, commandId);
        } catch (TimeoutException e) {
            // 超时不改变记录状态（设备可能稍后仍会响应，this.handleAck 会更新 DB）
            pendingService.completeExceptionally(commandId, e);
            log.warn("[SlamCommand] 等待首次 ACK 超时({}s): robotCode={}, commandId={}",
                    ACK_WAIT_TIMEOUT_SECONDS, robotCode, commandId);
        } catch (Exception e) {
            pendingService.completeExceptionally(commandId, e);
            log.error("[SlamCommand] 等待 ACK 异常: masterCode={}, commandId={}", masterCode, commandId, e);
        }

        // 重新读取最新状态（this.handleAck 可能已更新）
        return this.getById(record.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAck(String deviceCode, MqttMessageModel.SlamAck ack) {
        if (ack.getCommandId() == null) {
            log.warn("[SlamCommand] ack 缺少 commandId，忽略: deviceCode={}", deviceCode);
            return;
        }

        IotSlamCommandRecord record = this.getOne(
                new LambdaQueryWrapper<IotSlamCommandRecord>()
                        .eq(IotSlamCommandRecord::getCommandId, ack.getCommandId())
                        .last("LIMIT 1"));
        if (record == null) {
            log.warn("[SlamCommand] 未找到对应记录，忽略 ack: commandId={}", ack.getCommandId());
            // 未找到记录时也要释放 pending future，防止泄漏
            pendingService.completeExceptionally(ack.getCommandId(),
                    new IllegalStateException("未找到对应的指令记录"));
            return;
        }

        // 已处于终态则不再更新 DB，但仍需完成 pending future（防止 sendCommand 一直阻塞）
        if (DeviceConstant.SlamCommandStatus.COMPLETED.equals(record.getStatus())
                || DeviceConstant.SlamCommandStatus.FAILED.equals(record.getStatus())) {
            log.info("[SlamCommand] 记录已处于终态，忽略重复 ack: commandId={}, status={}", ack.getCommandId(), record.getStatus());
            pendingService.complete(ack.getCommandId(), ack);
            return;
        }

        // 更新记录
        record.setAckSuccess(ack.getSuccess());
        record.setAckCode(ack.getCode());
        record.setAckMessage(ack.getMessage());
        record.setAckSequence(ack.getSequence());
        record.setAckTotal(ack.getTotal());
        if (ack.getData() != null) {
            record.setAckDataJson(JSON.toJSONString(ack.getData()));
        }

        boolean failed = Boolean.FALSE.equals(ack.getSuccess()) || (ack.getCode() != null && ack.getCode() != 0);
        boolean isFinal = ack.getSequence() != null && ack.getTotal() != null
                && ack.getSequence().equals(ack.getTotal());

        if (failed) {
            record.setStatus(DeviceConstant.SlamCommandStatus.FAILED);
            record.setCompleteTime(LocalDateTime.now());
        } else if (isFinal) {
            record.setStatus(DeviceConstant.SlamCommandStatus.COMPLETED);
            record.setCompleteTime(LocalDateTime.now());
        } else {
            record.setStatus(DeviceConstant.SlamCommandStatus.PARTIAL);
        }

        this.updateById(record);
        log.info("[SlamCommand] ack 已处理: commandId={}, function={}, sequence={}/{}, status={}",
                ack.getCommandId(), ack.getFunction(), ack.getSequence(), ack.getTotal(), record.getStatus());

        // 终态时通过 WebSocket 推送结果给主控前端，type 为功能名称（如 SwitchMode）
        if (DeviceConstant.SlamCommandStatus.COMPLETED.equals(record.getStatus())
                || DeviceConstant.SlamCommandStatus.FAILED.equals(record.getStatus())) {
            try {
                webSocketServer.pushSlamAck(record.getMasterCode(), record.getFunctionName(),
                        objectMapper.writeValueAsString(record));
            } catch (Exception e) {
                log.warn("[SlamCommand] WebSocket 推送失败: commandId={}", record.getCommandId(), e);
            }
        }

        // 完成 pending future，解锁 sendCommand 的等待（仅第一次 ACK 有效，之后 map 中已无此 entry）
        boolean released = pendingService.complete(ack.getCommandId(), ack);
        if (released) {
            log.info("[SlamCommand] pending future 已释放: commandId={}", ack.getCommandId());
        }
    }

    @Override
    public void handleStates(String deviceCode, MqttMessageModel.SlamStates states) {
        try {
            String key = DeviceConstant.SlamRedisKey.SLAM_STATES_PREFIX + deviceCode;
            redisUtil.set(key, objectMapper.writeValueAsString(states));
            redisUtil.expire(key, SLAM_STATES_TTL);
            log.debug("[SlamStates] 状态已缓存: deviceCode={}, mode={}", deviceCode, states.getSlamNavMode());
            // TELEOP_ROBOT_TO_MASTER 由 StringRedisTemplate 写入纯字符串，不可用 RedisUtil(Jackson) 反序列化
            String masterCode = stringRedisTemplate.opsForValue()
                    .get(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + deviceCode);
            if (masterCode == null) {
                log.warn("[MasterCommandHandler] 未找到机器人 {} 对应的主控缓存，忽略消息", deviceCode);
                return;
            }
            webSocketServer.pushSlamStates(masterCode, objectMapper.writeValueAsString(states));
        } catch (Exception e) {
            log.error("[SlamStates] 状态缓存失败: deviceCode={}", deviceCode, e);
        }
    }

    @Override
    public IPage<IotSlamCommandRecord> pageRecords(Page<IotSlamCommandRecord> page, String deviceCode) {
        return this.page(page,
                new LambdaQueryWrapper<IotSlamCommandRecord>()
                        .eq(IotSlamCommandRecord::getMasterCode, deviceCode)
                        .orderByDesc(IotSlamCommandRecord::getSendTime));
    }
}
