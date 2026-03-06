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

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceConfigAckHandler {

    private final IotDeviceConfigMapper   configMapper;
    private final CommandEncryptService   encryptService;
    private final ObjectMapper            objectMapper;
    private final StringRedisTemplate     redisTemplate;
    private final IDeviceOperationLogService logService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.ConfigAck ack = objectMapper.readValue(decrypted, MqttMessageModel.ConfigAck.class);
        log.info("[ConfigAck] 设备[{}] commandId={} code={}", deviceCode, ack.getCommandId(), ack.getCode());

        redisTemplate.delete(DeviceConstant.RedisKey.CONFIG_SYNC_PREFIX + deviceCode + ":" + ack.getCommandId());

        int status = ack.getCode() == 0 ? DeviceConstant.ConfigSyncStatus.SUCCESS
                                        : DeviceConstant.ConfigSyncStatus.FAILED;
        configMapper.update(null, new LambdaUpdateWrapper<IotDeviceConfig>()
                .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING)
                .set(IotDeviceConfig::getSyncStatus, status)
                .set(IotDeviceConfig::getSyncTime, LocalDateTime.now()));

        logService.recordLog(null, deviceCode, DeviceConstant.OperationType.PARAM_MODIFY,
                "设备配置同步" + (ack.getCode() == 0 ? "成功" : "失败"),
                "{commandId:" + ack.getCommandId() + "}",
                DeviceConstant.OperationSource.DEVICE,
                ack.getCode() == 0 ? "SUCCESS" : "FAIL",
                ack.getCode() != 0 ? ack.getMessage() : null, null, null);
    }
}
