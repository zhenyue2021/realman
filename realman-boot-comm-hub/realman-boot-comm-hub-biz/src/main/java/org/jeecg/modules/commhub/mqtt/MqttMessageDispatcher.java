package org.jeecg.modules.commhub.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.commhub.contract.event.DeviceUplinkEvent;
import org.jeecg.modules.commhub.contract.event.EventKind;
import org.jeecg.modules.commhub.contract.event.Transport;
import org.jeecg.modules.commhub.service.DeviceStateSyncService;
import org.jeecg.modules.commhub.service.IUplinkEventService;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT 上行消息路由。收到消息后立即甩到线程池处理，不占用 Paho 内部回调线程
 * （否则会阻塞后续消息接收，甚至拖死整个连接）。路由方式沿用"正则提取
 * {@code {deviceCode}}/{@code {path}} + 字符串匹配路径"的既有模式，独立实现。
 *
 * <p>本轮范围：{@code status/report}（心跳/占用同步）、{@code ota/*}（归一化为
 * DeviceUplinkEvent 供 Webhook 转发，不做 OTA 业务解析——OTA 平台契约尚未落地）、
 * {@code bridge-ack}（HTTP-MQTT 桥接的 ACK 回执）、{@code $SYS} 上下线事件。
 * SLAM/WebRTC/数采等 Topic 见 {@code CommHubTopicConstants} 预留但本轮不处理。
 */
@Slf4j
@Component
public class MqttMessageDispatcher {

    private static final Pattern DEVICE_TOPIC = Pattern.compile("^device/([^/]+)/(.+)$");
    private static final Pattern SYS_CLIENT_EVENT = Pattern.compile("^\\$SYS/brokers/[^/]+/clients/([^/]+)/(connected|disconnected)$");

    public static final AtomicLong LAST_RECEIVED_TS = new AtomicLong(System.currentTimeMillis());

    private final DeviceStateSyncService deviceStateSyncService;
    private final IUplinkEventService uplinkEventService;
    private final MqttAckPendingService ackPendingService;
    private final Executor mqttMessageExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttMessageDispatcher(DeviceStateSyncService deviceStateSyncService,
                                  IUplinkEventService uplinkEventService,
                                  MqttAckPendingService ackPendingService,
                                  @Qualifier("mqttMessageExecutor") Executor mqttMessageExecutor) {
        this.deviceStateSyncService = deviceStateSyncService;
        this.uplinkEventService = uplinkEventService;
        this.ackPendingService = ackPendingService;
        this.mqttMessageExecutor = mqttMessageExecutor;
    }

    public void dispatch(String topic, String payload) {
        LAST_RECEIVED_TS.set(System.currentTimeMillis());
        mqttMessageExecutor.execute(() -> safeDispatch(topic, payload));
    }

    private void safeDispatch(String topic, String payload) {
        try {
            doDispatch(topic, payload);
        } catch (Exception e) {
            log.warn("[comm-hub] MQTT 消息处理异常 topic={}: {}", topic, e.getMessage(), e);
        }
    }

    private void doDispatch(String topic, String payload) {
        Matcher sysMatcher = SYS_CLIENT_EVENT.matcher(topic);
        if (sysMatcher.matches()) {
            handleSysClientEvent(sysMatcher.group(1), sysMatcher.group(2), payload);
            return;
        }

        Matcher deviceMatcher = DEVICE_TOPIC.matcher(topic);
        if (deviceMatcher.matches()) {
            handleDeviceTopic(deviceMatcher.group(1), deviceMatcher.group(2), payload);
            return;
        }

        log.debug("[comm-hub] 未识别 Topic，忽略 topic={}", topic);
    }

    private void handleSysClientEvent(String clientIdFromTopic, String eventType, String payload) {
        String deviceCode = extractClientId(payload, clientIdFromTopic);
        if (deviceCode == null) {
            return;
        }
        if ("connected".equals(eventType)) {
            deviceStateSyncService.handleConnected(deviceCode);
            publishUplinkEvent(deviceCode, EventKind.ONLINE, Transport.MQTT, Collections.emptyMap());
        } else {
            deviceStateSyncService.handleDisconnected(deviceCode, extractReason(payload));
            publishUplinkEvent(deviceCode, EventKind.OFFLINE, Transport.MQTT, Collections.emptyMap());
        }
    }

    private void handleDeviceTopic(String deviceCode, String path, String payload) {
        Map<String, Object> parsed = parsePayload(payload);
        switch (path) {
            case "status/report" -> deviceStateSyncService.handleStatusReport(deviceCode, parsed);
            case "ota/progress" -> publishUplinkEvent(deviceCode, EventKind.OTA_PROGRESS, Transport.MQTT, parsed);
            case "ota/status-report" -> publishUplinkEvent(deviceCode, EventKind.OTA_STATUS_REPORT, Transport.MQTT, parsed);
            case "ota/token-refresh" -> publishUplinkEvent(deviceCode, EventKind.TOKEN_REFRESH, Transport.MQTT, parsed);
            case "bridge-ack" -> handleBridgeAck(parsed, payload);
            default -> log.debug("[comm-hub] 未处理的设备端向 Topic 后缀，忽略 deviceCode={} path={}", deviceCode, path);
        }
    }

    private void handleBridgeAck(Map<String, Object> parsed, String rawPayload) {
        Object commandId = parsed.get("commandId");
        if (commandId == null) {
            log.warn("[comm-hub] bridge-ack 缺少 commandId 字段，忽略: {}", rawPayload);
            return;
        }
        ackPendingService.complete(commandId.toString(), rawPayload);
    }

    private void publishUplinkEvent(String deviceCode, EventKind eventKind, Transport transport, Map<String, Object> payload) {
        DeviceInfoDTO device = deviceStateSyncService.resolveDevice(deviceCode);
        if (device == null) {
            log.debug("[comm-hub] 未找到设备，跳过上行事件归一化 deviceCode={}", deviceCode);
            return;
        }
        DeviceUplinkEvent event = new DeviceUplinkEvent();
        event.setDeviceId(device.getDeviceId());
        event.setDeviceCode(deviceCode);
        event.setDeviceType(device.getDeviceType());
        event.setTenantId(device.getTenantId());
        event.setEventKind(eventKind);
        event.setTransport(transport);
        event.setPayload(payload);
        event.setReportedAt(LocalDateTime.now());
        uplinkEventService.ingest(event);
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("[comm-hub] payload 非 JSON 或解析失败，按空载荷处理: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String extractClientId(String payload, String fallback) {
        Map<String, Object> parsed = parsePayload(payload);
        Object clientId = parsed.get("clientid");
        if (clientId == null) {
            clientId = parsed.get("clientId");
        }
        return clientId != null ? clientId.toString() : fallback;
    }

    private String extractReason(String payload) {
        Map<String, Object> parsed = parsePayload(payload);
        Object reason = parsed.get("reason");
        return reason != null ? reason.toString() : null;
    }
}
