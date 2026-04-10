package org.jeecg.modules.device.service.impl.device;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 设备参数配置持久化与在线 MQTT 同步。
 * 事务由调用方 {@link org.jeecg.modules.device.service.impl.IotDeviceServiceImpl} 统一控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceConfigSyncService {

    private final IotDeviceSupport deviceSupport;
    private final IotDeviceConfigMapper configMapper;
    private final MqttPublisher mqttPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void setAndSyncConfig(String deviceId, Map<String, Object> params) {
        IotDevice device = deviceSupport.require(deviceId);
        String commandId = IdUtil.fastSimpleUUID();

        // 构建 upsert 列表（依赖唯一索引 uk_device_config(device_id, config_key)）
        LocalDateTime now = LocalDateTime.now();
        List<IotDeviceConfig> upsertList = new ArrayList<>(params.size());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            IotDeviceConfig cfg = new IotDeviceConfig();
            cfg.setId(IdUtil.getSnowflakeNextIdStr());
            cfg.setDeviceId(deviceId);
            cfg.setDeviceCode(device.getDeviceCode());
            cfg.setConfigKey(entry.getKey());
            cfg.setConfigValue(String.valueOf(entry.getValue()));
            cfg.setConfigType(entry.getKey());
            cfg.setSyncStatus(DeviceConstant.ConfigSyncStatus.PENDING);
            cfg.setCreateTime(now);
            cfg.setUpdateTime(now);
            upsertList.add(cfg);
        }
        // 1 条 SQL 完成所有 INSERT / UPDATE
        configMapper.batchUpsert(upsertList);

        if (DeviceConstant.DeviceStatus.ONLINE == device.getStatus()) {
            try {
                String payload = objectMapper.writeValueAsString(MqttMessageModel.ConfigPush.builder()
                        .commandId(commandId).params(params).timestamp(System.currentTimeMillis()).build());
                mqttPublisher.publishToDevice(device.getDeviceCode(),
                        String.format(DeviceConstant.MqttTopic.CONFIG_PUSH, device.getDeviceCode()), payload, MqttConstant.MQTT_QOS.QOS_1);
                redisTemplate.opsForValue().set(
                        DeviceConstant.RedisKey.CONFIG_SYNC_PREFIX + device.getDeviceCode() + ":" + commandId,
                        "pending", DeviceConstant.Timeout.CONFIG_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("配置推送失败: " + e.getMessage());
            }
        } else {
            log.warn("[Config] 设备[{}]离线，配置已保存待上线后同步", device.getDeviceCode());
        }
    }

    /**
     * 主控参数指令下发后写入 iot_device_config（待设备 ACK）。
     */
    public void upsertDeviceConfig(String deviceId, String deviceCode,
                                   String configKey, String configValue, String configType) {
        LocalDateTime now = LocalDateTime.now();
        IotDeviceConfig cfg = new IotDeviceConfig();
        cfg.setId(IdUtil.getSnowflakeNextIdStr());
        cfg.setDeviceId(deviceId);
        cfg.setDeviceCode(deviceCode);
        cfg.setConfigKey(configKey);
        cfg.setConfigValue(configValue);
        cfg.setConfigType(configType);
        cfg.setSyncStatus(0);
        cfg.setSyncTime(now);
        cfg.setCreateTime(now);
        cfg.setUpdateTime(now);
        configMapper.batchUpsert(List.of(cfg));
    }
}
