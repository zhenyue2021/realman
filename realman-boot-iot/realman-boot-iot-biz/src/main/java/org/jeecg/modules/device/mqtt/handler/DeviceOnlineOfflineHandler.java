package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.darwin.producer.DarwinDeviceStatusProducer;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.PendingSyncService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 设备上下线事件处理器（Topic: $SYS/brokers/+/clients/+/connected|disconnected）
 *
 * <p>EMQX 在设备 MQTT 连接建立/断开时发布 $SYS 系统事件。
 * 由于设备身份在连接层（HTTP Auth 回调）已完成鉴权，
 * 上线事件即代表设备已通过身份验证，无需再次校验。
 *
 * <p>上线处理流程：
 * <ol>
 *   <li>从 Topic 路径或 Payload 中提取 clientId（即 deviceCode）</li>
 *   <li>过滤平台服务账号（以 "iot-platform" 开头）</li>
 *   <li>查询 DB 验证设备存在性</li>
 *   <li>更新设备状态为 ONLINE，记录上线时间</li>
 *   <li>将 deviceCode 加入 Redis 在线集合</li>
 *   <li>WebSocket 推送上线事件给前端</li>
 *   <li>记录操作日志</li>
 * </ol>
 *
 * <p>下线处理流程：
 * <ol>
 *   <li>同上提取 deviceCode 并过滤平台账号</li>
 *   <li>更新设备状态为 OFFLINE，记录下线时间</li>
 *   <li>从 Redis 在线集合移除，删除状态缓存 Key</li>
 *   <li>WebSocket 推送下线事件给前端</li>
 *   <li>记录操作日志（含断线原因）</li>
 * </ol>
 *
 * @see PendingSyncService 设备上线后补推待同步消息（TODO：已预留，暂未在上线事件中调用）
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DeviceOnlineOfflineHandler {

    private final IotDeviceMapper deviceMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeviceWebSocketServer webSocketServer;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;
    private final PendingSyncService pendingSyncService;
    private final IIotDeviceRoomService roomService;

    /** Darwin 集成未启用时为 null */
    @Autowired(required = false)
    private DarwinDeviceStatusProducer darwinProducer;

    /**
     * 处理设备上线事件
     *
     * @param topic   EMQX $SYS connected Topic，格式：$SYS/brokers/{node}/clients/{clientId}/connected
     * @param payload EMQX 发布的 JSON，包含 clientid、username、peerhost 等字段
     */
    public void handleOnline(String topic, String payload) {
        try {
            // 1. 从 Topic 路径中提取 clientId（即 deviceCode），Payload 作为兜底
            String deviceCode = extractClientId(topic, payload);
            // 2. 过滤平台自身服务账号（iot-platform-server 等），只处理真实设备
            if (deviceCode == null || deviceCode.startsWith("iot-platform")) return;

            // 3. 查询设备是否存在
            IotDevice device = findDevice(deviceCode);
            if (device == null) return;

            // 4. 更新 DB 在线状态
            device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
            device.setLastOnlineTime(LocalDateTime.now());
            deviceMapper.updateById(device);

            // 5. 维护 Redis 在线集合
            redisTemplate.opsForSet().add(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);

            // 6. WebSocket 推送上线事件
            webSocketServer.pushDeviceOnlineStatus(deviceCode, true);

            log.info("[Online] 设备[{}]上线", deviceCode);

            // 7. 记录操作日志
            logService.recordLog(device.getId(), deviceCode, DeviceConstant.OperationType.DEVICE_ONLINE,
                    "设备MQTT连接建立，上线", null, DeviceConstant.OperationSource.DEVICE,
                    "SUCCESS", null, null, null);

            // 设备上线后补推离线期间待同步的配置/OTA 指令
            pendingSyncService.flushPendingMessages(deviceCode);

            // 向达尔文数采平台推送上线事件（Darwin 集成未启用时 darwinProducer 为 null）
            if (darwinProducer != null) {
                String deviceType = resolveDeviceType(device.getDeviceType());
                darwinProducer.sendOnlineEvent(deviceCode, deviceType, MDC.get("traceId"));
            }
        } catch (Exception e) {
            log.error("[Online] 处理异常", e);
        }
    }

    /**
     * 处理设备下线事件
     *
     * @param topic   EMQX $SYS disconnected Topic
     * @param payload EMQX 发布的 JSON，包含 clientid、reason 等字段
     */
    public void handleOffline(String topic, String payload) {
        // 1. 提取 deviceCode 并过滤平台账号
        String deviceCode = extractClientId(topic, payload);
        if (deviceCode == null || deviceCode.startsWith("iot-platform")) return;

        IotDevice device = findDevice(deviceCode);
        if (device == null) return;

        try {
            // 2. 更新 DB 离线状态
            device.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
            device.setLastOfflineTime(LocalDateTime.now());
            deviceMapper.updateById(device);

            // 3. 从 Redis 在线集合移除，并删除状态缓存（避免前端显示过期状态）
            redisTemplate.opsForSet().remove(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, deviceCode);
            redisTemplate.delete(DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + deviceCode);

            // 4. WebSocket 推送下线事件
            webSocketServer.pushDeviceOnlineStatus(deviceCode, false);

            // 5. 从 Payload 提取断线原因（EMQX 提供：normal/kicked/timeout 等）
            String reason = extractField(payload, "reason");
            log.info("[Offline] 设备[{}]下线, reason={}", deviceCode, reason);

            // 6. 记录操作日志（含断线原因，便于排查异常断线）
            logService.recordLog(device.getId(), deviceCode, DeviceConstant.OperationType.DEVICE_OFFLINE,
                    "设备MQTT连接断开，离线，原因: " + reason, null,
                    DeviceConstant.OperationSource.DEVICE, "SUCCESS", null, null, null);

            // 7. 设备离线时销毁房间：主控离线按 masterCode 销毁，机器人离线按 robotCode 反查销毁
            try {
                if (DeviceConstant.DeviceTypeInteger.CONTROLLER == device.getDeviceType()) {
                    roomService.destroyRoom(deviceCode);
                } else if (DeviceConstant.DeviceTypeInteger.ROBOT == device.getDeviceType()) {
                    roomService.destroyRoomByRobotCode(deviceCode);
                }
            } catch (Exception roomEx) {
                log.warn("[Offline] 房间销毁失败 deviceCode={}", deviceCode, roomEx);
            }

            // 向达尔文数采平台推送下线事件
            if (darwinProducer != null) {
                String deviceType = resolveDeviceType(device.getDeviceType());
                darwinProducer.sendOfflineEvent(deviceCode, deviceType, reason, MDC.get("traceId"));
            }
        } catch (Exception e) {
            log.error("[Offline] 处理异常", e);
        }
    }

    /**
     * 从 Topic 路径中提取 clientId
     *
     * <p>EMQX $SYS Topic 格式：$SYS/brokers/{node}/clients/{clientId}/connected
     * 优先从 Topic 的 "clients" 后一段提取，若失败则降级解析 Payload 的 "clientid" 字段。
     *
     * @param topic   EMQX $SYS Topic
     * @param payload EMQX 事件 JSON
     * @return clientId（即 deviceCode），提取失败返回 null
     */
    private String extractClientId(String topic, String payload) {
        String[] parts = topic.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("clients".equals(parts[i]) && i + 1 < parts.length) return parts[i + 1];
        }
        // 降级：从 Payload JSON 中提取 clientid 字段
        return extractField(payload, "clientid");
    }

    /**
     * 从 JSON 字符串中提取指定字段值
     *
     * @param payload JSON 字符串
     * @param field   字段名
     * @return 字段值字符串，解析失败返回 "unknown"
     */
    @SuppressWarnings("unchecked")
    private String extractField(String payload, String field) {
        try {
            return String.valueOf(((Map<?, ?>) objectMapper.readValue(payload, Map.class)).get(field));
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 按 deviceCode 查询设备，不存在则记录警告日志并返回 null
     */
    private IotDevice findDevice(String deviceCode) {
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null) {
            log.warn("[OnlineOffline] 未知设备 deviceCode={}", deviceCode);
        }
        return device;
    }

    private String resolveDeviceType(Integer deviceType) {
        if (deviceType == null) return "UNKNOWN";
        return DeviceConstant.DeviceTypeInteger.CONTROLLER == deviceType ? "MASTER" : "SLAVE";
    }
}
