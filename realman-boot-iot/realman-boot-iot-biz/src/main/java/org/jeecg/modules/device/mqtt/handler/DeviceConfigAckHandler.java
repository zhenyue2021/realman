package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/**
 * 设备配置同步结果确认处理器（Topic: device/{deviceCode}/config/ack）
 *
 * <p>当平台通过 {@code device/{deviceCode}/config/push} 推送参数配置后，
 * 设备执行参数应用，并将结果通过本 Topic 回复给平台。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解密密文 → 解析为 {@link MqttMessageModel.ConfigAck}</li>
 *   <li>清除 Redis 中对应的配置同步等待 Key（iot:config:sync:{deviceCode}:{commandId}）</li>
 *   <li>批量更新 DB 中该设备所有 PENDING 状态的配置记录：
 *       code=0 → SUCCESS，code≠0 → FAILED</li>
 *   <li>记录操作日志（含 commandId 便于追溯）</li>
 * </ol>
 *
 * <p>超时机制：若设备未在 {@link DeviceConstant.Timeout#CONFIG_SYNC_TIMEOUT_SECONDS} 秒内回复，
 * Redis Key 自然过期，平台无需主动处理（下次推送时会重新生成 commandId）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceConfigAckHandler {

    private final IotDeviceConfigMapper      configMapper;
    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final StringRedisTemplate        redisTemplate;
    private final IDeviceOperationLogService logService;

    /**
     * 处理设备配置同步确认消息
     *
     * @param deviceCode 设备编号（从 Topic 中提取）
     * @param payload    AES 加密的消息体密文
     * @throws Exception 解密失败或 JSON 解析失败时抛出
     */
    public void handle(String deviceCode, String payload) throws Exception {
        // 1. 解密 + 解析确认消息
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.ConfigAck ack = objectMapper.readValue(decrypted, MqttMessageModel.ConfigAck.class);
        log.info("[ConfigAck] 设备[{}] commandId={} code={}", deviceCode, ack.getCommandId(), ack.getCode());

        // 2. 清除配置同步等待 Key（表示此次推送已收到 ACK，无需再等待）
        redisTemplate.delete(DeviceConstant.RedisKey.CONFIG_SYNC_PREFIX + deviceCode + ":" + ack.getCommandId());

        // 3. 根据 code 确定最终同步状态：0=成功，非0=失败
        int status = ack.getCode() == 0 ? DeviceConstant.ConfigSyncStatus.SUCCESS
                                        : DeviceConstant.ConfigSyncStatus.FAILED;

        // 4. 批量更新该设备所有 PENDING 状态的配置记录（一次 commandId 对应一批 params）
        LambdaUpdateWrapper<IotDeviceConfig> updateWrapper = new LambdaUpdateWrapper<>(IotDeviceConfig.class)
                .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING)
                .set(IotDeviceConfig::getSyncStatus, status)
                .set(IotDeviceConfig::getSyncTime, LocalDateTime.now());
        configMapper.update(null, updateWrapper);

        // 5. 记录操作日志
        logService.recordLog(null, deviceCode, DeviceConstant.OperationType.PARAM_MODIFY,
                "设备配置同步" + (ack.getCode() == 0 ? "成功" : "失败"),
                "{commandId:" + ack.getCommandId() + "}",
                DeviceConstant.OperationSource.DEVICE,
                ack.getCode() == 0 ? "SUCCESS" : "FAIL",
                ack.getCode() != 0 ? ack.getMessage() : null, null, null);
    }
}
