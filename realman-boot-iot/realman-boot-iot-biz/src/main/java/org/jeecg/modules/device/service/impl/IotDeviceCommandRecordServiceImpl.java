package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDeviceCommandRecord;
import org.jeecg.modules.device.mapper.IotDeviceCommandRecordMapper;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceCommandRecordServiceImpl
        extends ServiceImpl<IotDeviceCommandRecordMapper, IotDeviceCommandRecord>
        implements IIotDeviceCommandRecordService {

    private static final int ACK_RETRY_TIMES = 3;
    private static final long ACK_RETRY_DELAY_MS = 50L;
    private static final long ORPHAN_ACK_WARN_TTL_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 独立事务提交，避免外层 {@code startTeleop/stopTeleop} 回滚时抹掉已下发的指令审计记录。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordSend(String commandId, String deviceId, String deviceCode,
                           String commandType, String deviceType, String operator,
                           String paramsJson) {
        if (commandId == null || commandId.isEmpty()) {
            return;
        }
        try {
            IotDeviceCommandRecord record = new IotDeviceCommandRecord();
            record.setCommandId(commandId);
            record.setDeviceId(deviceId);
            record.setDeviceCode(deviceCode);
            record.setCommandType(commandType);
            record.setDeviceType(deviceType);
            record.setStatus(DeviceConstant.CommandRecordStatus.PENDING);
            record.setOperator(operator);
            record.setParamsJson(paramsJson);
            record.setSendTime(LocalDateTime.now());
            save(record);
            log.info("[CommandRecord] 记录指令下发 commandId={} deviceCode={} type={}",
                    commandId, deviceCode, commandType);
        } catch (Exception e) {
            log.error("[CommandRecord] 记录指令下发失败 commandId={} deviceCode={}", commandId, deviceCode, e);
            throw new RuntimeException("记录指令下发失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void ack(String commandId, boolean success, String failReason, String ackDataJson) {
        if (commandId == null || commandId.isEmpty()) {
            return;
        }
        try {
            String newStatus = success ? DeviceConstant.CommandRecordStatus.SUCCESS
                    : DeviceConstant.CommandRecordStatus.FAIL;
            for (int attempt = 0; attempt < ACK_RETRY_TIMES; attempt++) {
                if (updatePendingAck(commandId, newStatus, success, failReason, ackDataJson)) {
                    log.info("[CommandRecord] 指令 ACK 已落库 commandId={} status={}", commandId, newStatus);
                    return;
                }
                if (attempt < ACK_RETRY_TIMES - 1) {
                    Thread.sleep(ACK_RETRY_DELAY_MS);
                }
            }
            logAckIgnored(commandId, success, failReason, newStatus);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[CommandRecord] 更新指令 ACK 被中断 commandId={}", commandId, e);
        } catch (Exception e) {
            log.error("[CommandRecord] 更新指令 ACK 失败 commandId={}", commandId, e);
        }
    }

    private boolean updatePendingAck(String commandId, String newStatus, boolean success,
                                   String failReason, String ackDataJson) {
        return update(new LambdaUpdateWrapper<IotDeviceCommandRecord>()
                .eq(IotDeviceCommandRecord::getCommandId, commandId)
                .eq(IotDeviceCommandRecord::getStatus, DeviceConstant.CommandRecordStatus.PENDING)
                .set(IotDeviceCommandRecord::getStatus, newStatus)
                .set(!success && failReason != null, IotDeviceCommandRecord::getFailReason, failReason)
                .set(ackDataJson != null, IotDeviceCommandRecord::getAckDataJson, ackDataJson)
                .set(IotDeviceCommandRecord::getAckTime, LocalDateTime.now()));
    }

    private void logAckIgnored(String commandId, boolean success, String failReason, String expectedStatus) {
        IotDeviceCommandRecord existing = getOne(new LambdaQueryWrapper<IotDeviceCommandRecord>()
                .eq(IotDeviceCommandRecord::getCommandId, commandId)
                .last("LIMIT 1"));
        if (existing == null) {
            logOrphanAck(commandId, success, failReason);
            return;
        }
        String status = existing.getStatus();
        if (DeviceConstant.CommandRecordStatus.SUCCESS.equals(status)
                || DeviceConstant.CommandRecordStatus.FAIL.equals(status)) {
            if (Objects.equals(status, expectedStatus)) {
                log.debug("[CommandRecord] 重复 ACK，已与库内终态一致，忽略 commandId={} status={}",
                        commandId, status);
            } else {
                log.warn("[CommandRecord] ACK 结果与库内终态不一致，忽略更新 commandId={} dbStatus={} ackSuccess={}",
                        commandId, status, success);
            }
            return;
        }
        if (DeviceConstant.CommandRecordStatus.TIMEOUT.equals(status)) {
            log.warn("[CommandRecord] 晚到 ACK，记录已为 TIMEOUT，忽略更新 commandId={} success={}",
                    commandId, success);
            return;
        }
        log.warn("[CommandRecord] ACK 未更新记录 commandId={} currentStatus={}", commandId, status);
    }

    /**
     * 无下发记录的 ACK（设备僵尸 ACK）：集群内 24h 仅 WARN 一次，后续降为 DEBUG。
     */
    private void logOrphanAck(String commandId, boolean success, String failReason) {
        String key = DeviceConstant.RedisKey.COMMAND_ACK_ORPHAN_WARN_PREFIX + commandId;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", ORPHAN_ACK_WARN_TTL_HOURS, TimeUnit.HOURS);
        if (Boolean.TRUE.equals(first)) {
            log.warn("[CommandRecord] 收到 ACK 但无平台下发记录，忽略更新 commandId={} success={} reason={}",
                    commandId, success, failReason);
        } else {
            log.debug("[CommandRecord] 重复孤儿 ACK，忽略 commandId={} success={}", commandId, success);
        }
    }

    @Override
    public int markTimeout() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusSeconds(DeviceConstant.Timeout.COMMAND_ACK_TIMEOUT_SECONDS);
        return getBaseMapper().update(null, new LambdaUpdateWrapper<IotDeviceCommandRecord>()
                .eq(IotDeviceCommandRecord::getStatus, DeviceConstant.CommandRecordStatus.PENDING)
                .lt(IotDeviceCommandRecord::getSendTime, threshold)
                .set(IotDeviceCommandRecord::getStatus, DeviceConstant.CommandRecordStatus.TIMEOUT)
                .set(IotDeviceCommandRecord::getUpdateTime, LocalDateTime.now()));
    }
}
