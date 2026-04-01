package org.jeecg.modules.device.service;


import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
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


/**
 * 离线消息补推服务
 *
 * <p>解决问题：设备离线期间，平台对其下发的配置修改或 OTA 升级通知无法送达。
 * 设备重新上线后，需将所有积压的待同步消息一次性推送过去，确保数据最终一致性。
 *
 * <p>触发时机：设备上线事件（{@link org.jeecg.modules.device.mqtt.handler.DeviceOnlineOfflineHandler#handleOnline}）
 * 触发后调用 {@link #flushPendingMessages}（当前已预留 TODO，待稳定后启用）。
 *
 * <p>补推内容：
 * <ul>
 *   <li>参数配置：查询该设备所有 PENDING 状态的 IotDeviceConfig 记录，合并为一条 ConfigPush 推送</li>
 *   <li>OTA 升级：查询该设备所有 NOTIFIED 状态的升级记录，重新发送 OtaNotify（含断点续传字节数）</li>
 * </ul>
 *
 * @see org.jeecg.modules.device.service.impl.IotOtaServiceImpl#executeUpgradeTask OTA 通知逻辑可复用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingSyncService {

    private final IotDeviceConfigMapper configMapper;
    private final IotOtaUpgradeRecordMapper recordMapper;
    /**
     * @Lazy 避免与 MqttConfig → MqttPublisher → PendingSyncService 的循环依赖
     */
    @Lazy
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 设备上线时补推所有待同步的离线消息
     *
     * <p>执行流程：
     * <ol>
     *   <li>查询该设备所有 PENDING 状态的配置记录，合并为一条 ConfigPush 消息推送</li>
     *   <li>查询该设备所有 NOTIFIED 状态的 OTA 升级记录，逐条重推 OtaNotify（含断点续传）</li>
     * </ol>
     *
     * <p>注意：本方法目前在 DeviceOnlineOfflineHandler 中以 TODO 形式预留，调用前需充分测试。
     * OTA 补推逻辑建议复用 IotOtaServiceImpl.executeUpgradeTask 的实现（含断点续传字节数注入）。
     *
     * @param deviceCode 刚上线的设备编号
     */
    public void flushPendingMessages(String deviceCode) {
        // ── 1. 补推待同步的参数配置 ──────────────────────────────────────────
        List<IotDeviceConfig> pendingConfigs = configMapper.selectList(
                new LambdaQueryWrapper<IotDeviceConfig>()
                        .eq(IotDeviceConfig::getDeviceCode, deviceCode)
                        .eq(IotDeviceConfig::getSyncStatus, DeviceConstant.ConfigSyncStatus.PENDING));

        if (!pendingConfigs.isEmpty()) {
            // 将所有待同步配置合并为一条消息（一次推送，减少 MQTT 交互次数）
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
                        String.format(DeviceConstant.MqttTopic.CONFIG_PUSH, deviceCode), payload, MqttConstant.MQTT_QOS.QOS_1);
                log.info("[PendingSync] 设备[{}]上线，补推{}条待同步配置", deviceCode, params.size());
            } catch (Exception e) {
                log.error("[PendingSync] 补推配置失败 deviceCode={}", deviceCode, e);
            }
        }

        // ── 2. 补推待执行的 OTA 升级通知 ────────────────────────────────────
        // 查询已通知（NOTIFIED）但设备可能未收到的升级记录（设备离线时发的通知）
        List<IotOtaUpgradeRecord> pendingOta = recordMapper.selectList(
                new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .eq(IotOtaUpgradeRecord::getDeviceCode, deviceCode)
                        .eq(IotOtaUpgradeRecord::getUpgradeStatus, DeviceConstant.OtaUpgradeStatus.NOTIFIED));

        for (IotOtaUpgradeRecord record : pendingOta) {
            // TODO: 复用 IotOtaServiceImpl.executeUpgradeTask 的发送逻辑（含断点续传字节数注入）
            log.info("[PendingSync] 设备[{}]上线，重推OTA升级通知 recordId={}", deviceCode, record.getId());
        }
    }
}
