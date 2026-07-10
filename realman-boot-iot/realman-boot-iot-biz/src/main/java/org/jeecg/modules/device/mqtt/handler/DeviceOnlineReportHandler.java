package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.impl.master.TeleopRelationCacheService;
import org.jeecg.modules.device.util.OperationLogDetail;
import org.jeecg.modules.device.mqtt.model.DeviceOnlineReport;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 机器人设备主动上线信息上报处理器（Topic: device/{deviceCode}/datacollect/deviceOnline）
 *
 * <p>设备 MQTT 连接建立后主动推送本消息，携带型号、固件版本及当前位置。
 * 平台收到后仅同步设备元数据（型号/固件/坐标/last_online_time），<b>不</b>修改 DB {@code status}。
 *
 * <p>在线态与 Darwin MQ 推送由 {@link DeviceOnlineOfflineHandler}（$SYS）统一处理；
 * 收到本 Topic 后会向已绑定且在线的主控 WebSocket 推送 {@code ROBOT_ONLINE_STATUS}。
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
    private final IDeviceOperationLogService logService;
    private final TeleopRelationCacheService teleopRelationCacheService;
    private final StringRedisTemplate redisTemplate;
    private final DeviceWebSocketServer webSocketServer;

    /**
     * 处理设备主动上线上报消息
     *
     * @param deviceCode 设备编码（从 Topic 中提取）
     * @param payload    明文 JSON，结构见 {@link DeviceOnlineReport}
     */
    public void handle(String deviceCode, String payload) {
        DeviceOnlineReport report;
        try {
            report = objectMapper.readValue(payload, DeviceOnlineReport.class);
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
        deviceMapper.updateById(device);
        log.info("[DeviceOnlineReport] 设备元数据已同步 deviceCode={} prevStatus={} status={}",
                deviceCode, prevStatus, device.getStatus());
        String topic = "device/" + deviceCode + "/" + DataCollectConstant.MQTT_UP_DEVICE_ONLINE;
        logService.recordLog(device.getId(), deviceCode,
                DeviceConstant.OperationType.DEVICE_METADATA,
                "设备上报上线元数据已同步: model=" + device.getDeviceModel()
                        + ", firmware=" + device.getFirmwareVersion(),
                OperationLogDetail.ofTopic(topic),
                DeviceConstant.OperationSource.DEVICE, "SUCCESS", null, null, null);
        notifyBoundMasterIfOnline(device, deviceCode);
    }

    /**
     * 机器人上报 deviceOnline 后，通知已绑定且 MQTT 在线的主控。
     */
    private void notifyBoundMasterIfOnline(IotDevice robot, String robotCode) {
        if (DeviceConstant.DeviceTypeInteger.ROBOT != robot.getDeviceType()) {
            return;
        }
        String masterCode = teleopRelationCacheService.getMasterByRobot(robotCode);
        if (masterCode == null || masterCode.isBlank()) {
            log.debug("[DeviceOnlineReport] 无绑定主控，跳过 WS 通知 robot={}", robotCode);
            return;
        }
        boolean masterOnline = Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, masterCode));
        if (!masterOnline) {
            log.debug("[DeviceOnlineReport] 绑定主控不在线，跳过 WS 通知 robot={} master={}", robotCode, masterCode);
            return;
        }
        try {
            String robotJson = objectMapper.writeValueAsString(robot);
            webSocketServer.pushRobotOnlineToMaster(masterCode, robotJson);
            log.info("[DeviceOnlineReport] 已通知主控机器人上线 robot={} master={}", robotCode, masterCode);
        } catch (Exception e) {
            log.warn("[DeviceOnlineReport] 通知主控机器人上线失败 robot={} master={}", robotCode, masterCode, e);
        }
    }

    private void updateDeviceFields(IotDevice device, DeviceOnlineReport report) {
        // last_online_time：优先使用设备上报的时间戳，回退到当前时间
        if (report.getTimestamp() > 0) {
            device.setLastOnlineTime(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(report.getTimestamp()), ZoneId.systemDefault()));
        } else {
            device.setLastOnlineTime(LocalDateTime.now());
        }

        DeviceOnlineReport.OnlinePayload p = report.getPayload();
        if (p == null) return;

        // device_model ← payload.deviceType
        if (p.getDeviceType() != null && !p.getDeviceType().isBlank()) {
            device.setDeviceModel(p.getDeviceType());
        }
        // firmware_version ← payload.version
        if (p.getVersion() != null && !p.getVersion().isBlank()) {
            device.setFirmwareVersion(p.getVersion());
        }

        DeviceOnlineReport.Location loc = p.getLocation();
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
