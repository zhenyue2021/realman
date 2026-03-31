package org.jeecg.modules.device.service.impl;

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
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.mapper.IotSlamCommandRecordMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IIotSlamCommandService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotSlamCommandServiceImpl extends ServiceImpl<IotSlamCommandRecordMapper, IotSlamCommandRecord>
        implements IIotSlamCommandService {

    /** SLAM 状态 Redis TTL（秒），5 分钟 */
    private static final long SLAM_STATES_TTL = 300L;

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final RedisUtil redisUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotSlamCommandRecord sendCommand(String deviceCode, String function, Map<String, Object> params) {
        String commandId = "slam_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 构建下行报文
        MqttMessageModel.SlamRequest request = MqttMessageModel.SlamRequest.builder()
                .commandId(commandId)
                .function(function)
                .params(params)
                .build();

        // 创建记录
        IotSlamCommandRecord record = new IotSlamCommandRecord();
        record.setDeviceCode(deviceCode);
        record.setCommandId(commandId);
        record.setFunction(function);
        record.setParamsJson(params != null ? JSON.toJSONString(params) : null);
        record.setStatus(DeviceConstant.SlamCommandStatus.PENDING);
        record.setSendTime(LocalDateTime.now());
        this.save(record);

        // 发送 MQTT
        try {
            String topic = String.format(DeviceConstant.MqttTopic.SLAM_REQUEST, deviceCode);
            String payload = objectMapper.writeValueAsString(request);
            mqttPublisher.publishToDevice(deviceCode, topic, payload, 1);
            log.info("[SlamCommand] 指令已发送: deviceCode={}, commandId={}, function={}", deviceCode, commandId, function);
        } catch (Exception e) {
            log.error("[SlamCommand] MQTT 发送失败: deviceCode={}, commandId={}", deviceCode, commandId, e);
            // 发送失败直接标记为 FAILED
            record.setStatus(DeviceConstant.SlamCommandStatus.FAILED);
            record.setAckMessage("MQTT 发送失败: " + e.getMessage());
            record.setCompleteTime(LocalDateTime.now());
            this.updateById(record);
        }

        return record;
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
            return;
        }

        // 已经是终态则不再更新
        if (DeviceConstant.SlamCommandStatus.COMPLETED.equals(record.getStatus())
                || DeviceConstant.SlamCommandStatus.FAILED.equals(record.getStatus())) {
            log.info("[SlamCommand] 记录已处于终态，忽略 ack: commandId={}, status={}", ack.getCommandId(), record.getStatus());
            return;
        }

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
    }

    @Override
    public void handleStates(String deviceCode, MqttMessageModel.SlamStates states) {
        try {
            String key = DeviceConstant.SlamRedisKey.SLAM_STATES_PREFIX + deviceCode;
            redisUtil.set(key, objectMapper.writeValueAsString(states));
            redisUtil.expire(key, SLAM_STATES_TTL);
            log.debug("[SlamStates] 状态已缓存: deviceCode={}, mode={}", deviceCode, states.getSlamNavMode());
        } catch (Exception e) {
            log.error("[SlamStates] 状态缓存失败: deviceCode={}", deviceCode, e);
        }
    }

    @Override
    public IPage<IotSlamCommandRecord> pageRecords(Page<IotSlamCommandRecord> page, String deviceCode) {
        return this.page(page,
                new LambdaQueryWrapper<IotSlamCommandRecord>()
                        .eq(IotSlamCommandRecord::getDeviceCode, deviceCode)
                        .orderByDesc(IotSlamCommandRecord::getSendTime));
    }
}
