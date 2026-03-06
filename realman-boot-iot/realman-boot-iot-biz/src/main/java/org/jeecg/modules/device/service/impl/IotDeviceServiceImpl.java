package org.jeecg.modules.device.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.*;
import org.jeecg.modules.device.mapper.*;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 设备管理Service实现
 * <p>
 * 鉴权说明：设备通过deviceSecret直连EMQX，无需应用层登录。
 * 下行消息使用per-device AES-256密钥加密（由deviceSecret派生）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceServiceImpl extends ServiceImpl<IotDeviceMapper, IotDevice>
        implements IIotDeviceService {

    private final IotDeviceMapper deviceMapper;
    private final IotDeviceConfigMapper configMapper;
    private final IotDeviceStatusMapper statusMapper;
    private final DeviceSecretService secretService;
    private final CommandEncryptService encryptService;
    private final MqttPublisher mqttPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDevice addDevice(IotDevice device) {
        long cnt = deviceMapper.selectCount(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, device.getDeviceCode()));
        if (cnt > 0) throw new RuntimeException("设备编号已存在: " + device.getDeviceCode());
        device.setStatus(DeviceConstant.DeviceStatus.INACTIVE);
        device.setCreateTime(LocalDateTime.now());
        deviceMapper.insert(device);
        // 自动生成deviceSecret，即DigestUtil.md5Hex(device.getDeviceCode())，并放缓存
        String generateSecret = secretService.generateSecret(device.getDeviceCode());
        device.setDeviceSecret(generateSecret);
        logService.recordLog(device.getId(), device.getDeviceCode(),
                DeviceConstant.OperationType.DEVICE_REGISTER,
                "新设备注册: " + device.getDeviceCode(), null,
                DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, null, null);
        return device;
    }

    @Override
    public IPage<IotDevice> queryDevicePage(Page<IotDevice> page, String deviceName,
                                            Integer deviceType, Integer status, String productId) {
        return deviceMapper.selectDevicePage(page, deviceName, deviceType, status, productId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAndSyncConfig(String deviceId, Map<String, Object> params) {
        IotDevice device = require(deviceId);
        String commandId = IdUtil.fastSimpleUUID();
        params.forEach((key, value) -> {
            IotDeviceConfig cfg = configMapper.selectOne(new LambdaQueryWrapper<IotDeviceConfig>()
                    .eq(IotDeviceConfig::getDeviceId, deviceId)
                    .eq(IotDeviceConfig::getConfigKey, key));
            if (cfg != null) {
                cfg.setConfigValue(String.valueOf(value));
                cfg.setSyncStatus(DeviceConstant.ConfigSyncStatus.PENDING);
                configMapper.updateById(cfg);
            } else {
                cfg = new IotDeviceConfig();
                cfg.setDeviceId(deviceId);
                cfg.setDeviceCode(device.getDeviceCode());
                cfg.setConfigKey(key);
                cfg.setConfigValue(String.valueOf(value));
                cfg.setConfigType("string");
                cfg.setSyncStatus(DeviceConstant.ConfigSyncStatus.PENDING);
                cfg.setCreateTime(LocalDateTime.now());
                configMapper.insert(cfg);
            }
        });

        if (DeviceConstant.DeviceStatus.ONLINE == device.getStatus()) {
            try {
                String payload = objectMapper.writeValueAsString(MqttMessageModel.ConfigPush.builder()
                        .commandId(commandId).params(params).timestamp(System.currentTimeMillis()).build());
                mqttPublisher.publishToDevice(device.getDeviceCode(),
                        String.format(DeviceConstant.MqttTopic.CONFIG_PUSH, device.getDeviceCode()), payload, 1);
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


    @Override
    public Map<String, Object> getDeviceMonitorStatus(String deviceId) {
        IotDevice device = require(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("deviceCode", device.getDeviceCode());
        result.put("status", device.getStatus());
        result.put("lastOnlineTime", device.getLastOnlineTime());
        String cached = redisTemplate.opsForValue().get(
                DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + device.getDeviceCode());
        if (cached != null) {
            try {
                result.putAll(objectMapper.readValue(cached, Map.class));
                result.put("dataSource", "realtime");
            } catch (Exception ignored) {
            }
        } else {
            IotDeviceStatus s = statusMapper.selectOne(new LambdaQueryWrapper<IotDeviceStatus>()
                    .eq(IotDeviceStatus::getDeviceId, deviceId)
                    .orderByDesc(IotDeviceStatus::getReceiveTime).last("LIMIT 1"));
            if (s != null) {
                result.put("temperature", s.getTemperature());
                result.put("humidity", s.getHumidity());
                result.put("batteryLevel", s.getBatteryLevel());
                result.put("signalStrength", s.getSignalStrength());
                result.put("longitude", s.getLongitude());
                result.put("latitude", s.getLatitude());
                result.put("runStatus", s.getRunStatus());
                result.put("reportTime", s.getReportTime());
                result.put("dataSource", "database");
            }
        }
        return result;
    }

    @Override
    public void remoteRestart(String deviceId, String reason, String operator) {
        IotDevice device = require(deviceId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus())
            throw new RuntimeException("设备不在线");
        String commandId = IdUtil.fastSimpleUUID();
        try {
            String payload = objectMapper.writeValueAsString(MqttMessageModel.RemoteRestartCommand.builder()
                    .commandId(commandId).reason(reason).timestamp(System.currentTimeMillis()).build());
            mqttPublisher.publishToDevice(device.getDeviceCode(),
                    String.format(DeviceConstant.MqttTopic.REMOTE_RESTART, device.getDeviceCode()), payload, 1);
            logService.recordLog(deviceId, device.getDeviceCode(),
                    DeviceConstant.OperationType.REMOTE_RESTART,
                    "远程重启: " + reason, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
        } catch (Exception e) {
            throw new RuntimeException("发送重启指令失败: " + e.getMessage());
        }
    }

    @Override
    public void changeDeviceStatus(String deviceId, Integer status, String operator) {
        IotDevice device = require(deviceId);
        device.setStatus(status);
        deviceMapper.updateById(device);
        if (DeviceConstant.DeviceStatus.DISABLED == status) {
            secretService.evict(device.getDeviceCode());
            encryptService.evictCache(device.getDeviceCode());
        }
    }

    @Override
    public List<Map<String, Object>> batchGetOnlineStatus(List<String> deviceIds) {
        return deviceIds.stream().map(id -> {
            IotDevice d = deviceMapper.selectById(id);
            Map<String, Object> item = new LinkedHashMap<>();
            if (d != null) {
                boolean online = Boolean.TRUE.equals(redisTemplate.opsForSet()
                        .isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, d.getDeviceCode()));
                item.put("deviceId", id);
                item.put("deviceCode", d.getDeviceCode());
                item.put("status", d.getStatus());
                item.put("onlineInRedis", online);
                item.put("lastOnlineTime", d.getLastOnlineTime());
            }
            return item;
        }).collect(Collectors.toList());
    }

    private IotDevice require(String deviceId) {
        IotDevice d = deviceMapper.selectById(deviceId);
        if (d == null) throw new RuntimeException("设备不存在: " + deviceId);
        return d;
    }
}
