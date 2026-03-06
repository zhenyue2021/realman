package org.jeecg.modules.device.service;


import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeRecordMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class PendingSyncService {

    private final IotDeviceConfigMapper configMapper;
    private final IotOtaUpgradeRecordMapper recordMapper;
    @Lazy
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper           objectMapper;

    /**
     * 设备上线时调用，将所有待同步的数据一次性推送过去
     */
    public void flushPendingMessages(String deviceCode) {
        // 1. 补推待同步的参数配置
        List<IotDeviceConfig> pendingConfigs = configMapper.selectList(
                new LambdaQueryWrapper<IotDeviceConfig>()
                        .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                        .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING));

        if (!pendingConfigs.isEmpty()) {
            Map<String, Object> params = pendingConfigs.stream()
                    .collect(Collectors.toMap(IotDeviceConfig::getConfigKey,
                            c -> (Object) c.getConfigValue()));
            String commandId = IdUtil.fastSimpleUUID();
            try {
                String payload = objectMapper.writeValueAsString(
                        MqttMessageModel.ConfigPush.builder()
                                .commandId(commandId).params(params)
                                .timestamp(System.currentTimeMillis()).build());
                mqttPublisher.publishToDevice(deviceCode,
                        String.format(DeviceConstant.MqttTopic.CONFIG_PUSH, deviceCode), payload, 1);
                log.info("[PendingSync] 设备[{}]上线，补推{}条待同步配置", deviceCode, params.size());
            } catch (Exception e) {
                log.error("[PendingSync] 补推配置失败 deviceCode={}", deviceCode, e);
            }
        }

        // 2. 补推待执行的OTA升级通知
        List<IotOtaUpgradeRecord> pendingOta = recordMapper.selectList(
                new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .eq(IotOtaUpgradeRecord::getDeviceCode, deviceCode)
                        .eq(IotOtaUpgradeRecord::getUpgradeStatus, DeviceConstant.OtaUpgradeStatus.NOTIFIED));

        for (IotOtaUpgradeRecord record : pendingOta) {
            // TODO OTA补推逻辑（含断点续传字节数）已在 executeUpgradeTask 中实现，复用即可
            log.info("[PendingSync] 设备[{}]上线，重推OTA升级通知 recordId={}", deviceCode, record.getId());
        }
    }
}
