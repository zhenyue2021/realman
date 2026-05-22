package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.datacollect.producer.DeviceStatusProducer;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 机器人设备主动上线信息上报处理器（Topic: device/{deviceCode}/datacollect/deviceOnline）
 *
 * <p>设备 MQTT 连接建立后主动推送本消息，携带型号、固件版本及当前位置。
 * 平台收到后：
 * <ol>
 *   <li>将消息字段同步回 iot_device 表（含 status=ONLINE、last_online_time 及型号/固件/坐标等）</li>
 *   <li>若 Darwin 集成已启用，向 RocketMQ 推送 ONLINE 事件（此为唯一推送点，DeviceStatusHandler 不再推送）</li>
 * </ol>
 *
 * <p>消息体明文 JSON，无 AES 加密。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DeviceOnlineReportHandler {

    private final IotDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final DeviceDbStatusCache dbStatusCache;

    /** darwin.integration.enabled=false 时 Bean 不存在，注入 null */
    @Autowired(required = false)
    private DeviceStatusProducer deviceStatusProducer;

    /**
     * 处理设备主动上线上报消息
     *
     * @param deviceCode 设备编码（从 Topic 中提取）
     * @param payload    明文 JSON，结构见 {@link MqttMessageModel.DeviceOnlineReport}
     */
    public void handle(String deviceCode, String payload) {
        MqttMessageModel.DeviceOnlineReport report;
        try {
            report = objectMapper.readValue(payload, MqttMessageModel.DeviceOnlineReport.class);
        } catch (Exception e) {
            log.warn("[DeviceOnlineReport] JSON 解析失败 deviceCode={} payload={}", deviceCode, payload, e);
            return;
        }

        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null) {
            log.warn("[DeviceOnlineReport] 未知设备 deviceCode={}", deviceCode);
            return;
        }

        Integer prevStatus = device.getStatus();
        updateDeviceFields(device, report);
        applyOnlineStatus(device);
        deviceMapper.updateById(device);
        if (!Objects.equals(device.getStatus(), DeviceConstant.DeviceStatus.DISABLED)) {
            dbStatusCache.setStatus(deviceCode, DeviceConstant.DeviceStatus.ONLINE);
        }
        log.info("[DeviceOnlineReport] 设备信息已同步 deviceCode={} prevStatus={} status={}",
                deviceCode, prevStatus, device.getStatus());

        // 仅机器人设备推送上线 MQ 事件
        if (deviceStatusProducer != null
                && DeviceConstant.DeviceTypeInteger.ROBOT == device.getDeviceType()) {
            String tenant = device.getTenantId() != null ? String.valueOf(device.getTenantId()) : "";
            deviceStatusProducer.sendOnlineEvent(
                    tenant, deviceCode, "SLAVE", device.getDeviceModel(), MDC.get("traceId"));
        }
    }

    /** 非禁用设备在主动上线上报时同步为 ONLINE（含未激活、离线）。 */
    private static void applyOnlineStatus(IotDevice device) {
        if (Objects.equals(device.getStatus(), DeviceConstant.DeviceStatus.DISABLED)) {
            return;
        }
        device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
    }

    private void updateDeviceFields(IotDevice device, MqttMessageModel.DeviceOnlineReport report) {
        // last_online_time：优先使用设备上报的时间戳，回退到当前时间
        if (report.getTimestamp() > 0) {
            device.setLastOnlineTime(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(report.getTimestamp()), ZoneId.systemDefault()));
        } else {
            device.setLastOnlineTime(LocalDateTime.now());
        }

        MqttMessageModel.DeviceOnlineReport.OnlinePayload p = report.getPayload();
        if (p == null) return;

        // device_model ← payload.deviceType
        if (p.getDeviceType() != null && !p.getDeviceType().isBlank()) {
            device.setDeviceModel(p.getDeviceType());
        }
        // firmware_version ← payload.version
        if (p.getVersion() != null && !p.getVersion().isBlank()) {
            device.setFirmwareVersion(p.getVersion());
        }

        MqttMessageModel.DeviceOnlineReport.Location loc = p.getLocation();
        if (loc == null) return;

        // latitude / longitude：有值才覆盖，避免设备传 0.0 覆盖历史有效坐标
        if (loc.getLatitude() != null && loc.getLatitude() != 0.0) {
            device.setLatitude(BigDecimal.valueOf(loc.getLatitude()));
        }
        if (loc.getLongitude() != null && loc.getLongitude() != 0.0) {
            device.setLongitude(BigDecimal.valueOf(loc.getLongitude()));
        }
    }
}
