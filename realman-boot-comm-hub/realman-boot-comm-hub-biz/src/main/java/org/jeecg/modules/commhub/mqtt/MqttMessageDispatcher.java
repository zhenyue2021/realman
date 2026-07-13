package org.jeecg.modules.commhub.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.constant.CommHubTopicConstants;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.event.DeviceUplinkEvent;
import org.jeecg.modules.commhub.contract.event.EventKind;
import org.jeecg.modules.commhub.contract.event.Transport;
import org.jeecg.modules.commhub.entity.CommHubTopicRoute;
import org.jeecg.modules.commhub.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.commhub.service.CommHubTopicRouteRegistry;
import org.jeecg.modules.commhub.service.DeviceStateSyncService;
import org.jeecg.modules.commhub.service.IUplinkEventService;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenRefreshRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceTokenRefreshResult;
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
 * （否则会阻塞后续消息接收，甚至拖死整个连接）。Topic 匹配沿用"正则提取
 * {@code {deviceCode}}/{@code {path}}"的既有模式；{@code path} -&gt; 处理类别的映射
 * 经 {@link CommHubTopicRouteRegistry} 落库 {@code comm_hub_topic_route} 可配置
 * （原硬编码 switch，见设备通信中台详细设计已知限制第 6 项），各处理类别对应的
 * 实际逻辑仍是本类固定方法。
 *
 * <p>本轮范围：{@code status/report}（心跳/占用同步）、{@code ota/*}（归一化为
 * DeviceUplinkEvent 供 Webhook 转发，不做 OTA 业务解析——OTA 平台契约尚未落地）、
 * {@code bridge-ack}（HTTP-MQTT 桥接的 ACK 回执）、{@code $SYS} 上下线事件。
 * SLAM/WebRTC/数采等 Topic 见 {@code CommHubTopicConstants} 预留但本轮不处理。
 *
 * <p>{@code ota/token-refresh} 是唯一需要闭环处理的上行 Topic：归一化为
 * DeviceUplinkEvent 之外，还要实际转发 device-mgmt 完成续签，并把新 Token 下行
 * 回传给设备（同一 Topic 双向复用，见 {@code CommHubTopicConstants#TOPIC_OTA_TOKEN_REFRESH}
 * 注释），这里补齐了 Phase 1 提交时"只做事件归一化"的已知限制。
 */
@Slf4j
@Component
public class MqttMessageDispatcher {

    private static final Pattern DEVICE_TOPIC = Pattern.compile("^device/([^/]+)/(.+)$");
    private static final Pattern SYS_CLIENT_EVENT = Pattern.compile("^\\$SYS/brokers/[^/]+/clients/([^/]+)/(connected|disconnected)$");
    private static final String FIELD_DEVICE_TOKEN = "deviceToken";
    private static final String FIELD_TOKEN_EXPIRES_AT = "tokenExpiresAt";

    public static final AtomicLong LAST_RECEIVED_TS = new AtomicLong(System.currentTimeMillis());

    private final DeviceStateSyncService deviceStateSyncService;
    private final IUplinkEventService uplinkEventService;
    private final MqttAckPendingService ackPendingService;
    private final MqttPublisher mqttPublisher;
    private final DeviceMgmtFeignClient deviceMgmtFeignClient;
    private final CommHubTopicRouteRegistry topicRouteRegistry;
    private final Executor mqttMessageExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttMessageDispatcher(DeviceStateSyncService deviceStateSyncService,
                                  IUplinkEventService uplinkEventService,
                                  MqttAckPendingService ackPendingService,
                                  MqttPublisher mqttPublisher,
                                  DeviceMgmtFeignClient deviceMgmtFeignClient,
                                  CommHubTopicRouteRegistry topicRouteRegistry,
                                  @Qualifier("mqttMessageExecutor") Executor mqttMessageExecutor) {
        this.deviceStateSyncService = deviceStateSyncService;
        this.uplinkEventService = uplinkEventService;
        this.ackPendingService = ackPendingService;
        this.mqttPublisher = mqttPublisher;
        this.deviceMgmtFeignClient = deviceMgmtFeignClient;
        this.topicRouteRegistry = topicRouteRegistry;
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

    /**
     * Topic 后缀 -&gt; 处理类别（routeType）的映射查 {@link CommHubTopicRouteRegistry}
     * （落库 {@code comm_hub_topic_route}，可配置，见类注释）；各 routeType 对应的
     * 实际处理逻辑仍是本类固定方法，不是脚本/规则引擎——可配置的边界到此为止。
     */
    private void handleDeviceTopic(String deviceCode, String path, String payload) {
        Map<String, Object> parsed = parsePayload(payload);
        CommHubTopicRoute route = topicRouteRegistry.resolve(path);
        if (route == null) {
            log.debug("[comm-hub] 未注册或已禁用的设备端向 Topic 后缀，忽略 deviceCode={} path={}", deviceCode, path);
            return;
        }
        switch (route.getRouteType()) {
            case "SSOT_ONLY" -> deviceStateSyncService.handleStatusReport(deviceCode, parsed);
            case "SSOT_AND_EVENT" -> handleSsotAndEvent(deviceCode, parsed, route.getEventKind());
            case "EVENT_ONLY" -> publishUplinkEvent(deviceCode, EventKind.valueOf(route.getEventKind()), Transport.MQTT, parsed);
            case "TOKEN_REFRESH" -> handleTokenRefresh(deviceCode, parsed);
            case "BRIDGE_ACK" -> handleBridgeAck(path, parsed, payload);
            case "IGNORE" -> log.debug("[comm-hub] Topic 路由标记为 IGNORE，忽略 deviceCode={} path={}", deviceCode, path);
            default -> log.warn("[comm-hub] 未知 route_type={}，忽略 deviceCode={} path={}", route.getRouteType(), deviceCode, path);
        }
    }

    /**
     * {@code SSOT_AND_EVENT}（如 {@code ota/heartbeat}，PRD 心跳接口对齐 Topic，见设备
     * 通信中台详细设计 2.2/5.2）：与 {@code status/report}（{@code SSOT_ONLY}）共用同一套
     * SSOT 同步逻辑（资源快照/占用态），额外把上报归一化为 {@code DeviceUplinkEvent}，
     * 使其可经 4.3.2 的 Webhook/轮询转发给已订阅的第三方——这是 {@code SSOT_ONLY} 不做的
     * （{@code status/report} 是仅供设备基座内部同步的既有 Topic，未在 5.2 映射表里
     * 承诺对外可观测）。
     */
    private void handleSsotAndEvent(String deviceCode, Map<String, Object> parsed, String eventKindName) {
        deviceStateSyncService.handleStatusReport(deviceCode, parsed);
        publishUplinkEvent(deviceCode, EventKind.valueOf(eventKindName), Transport.MQTT, parsed);
    }

    /**
     * {@code ota/token-refresh} 双闭环：先按既有约定归一化为 DeviceUplinkEvent（供 Webhook
     * 订阅方观测），再实际转发 device-mgmt 完成续签，成功后把新 Token 下行回传给设备
     * （同一 Topic 双向复用）。device-mgmt 不可达或旧 Token 已失效时只记录告警，不重试——
     * 设备会在下一次心跳/上行时用同一旧 Token 再次触发续签。
     */
    private void handleTokenRefresh(String deviceCode, Map<String, Object> parsed) {
        publishUplinkEvent(deviceCode, EventKind.TOKEN_REFRESH, Transport.MQTT, parsed);

        Object oldTokenValue = parsed.get(FIELD_DEVICE_TOKEN);
        String oldToken = oldTokenValue != null ? oldTokenValue.toString() : null;
        if (oldToken == null || oldToken.isBlank()) {
            log.warn("[comm-hub] ota/token-refresh 缺少 {} 字段，跳过续签 deviceCode={}", FIELD_DEVICE_TOKEN, deviceCode);
            return;
        }

        DeviceTokenRefreshRequest request = new DeviceTokenRefreshRequest();
        request.setOldToken(oldToken);
        Result<DeviceTokenRefreshResult> result;
        try {
            result = deviceMgmtFeignClient.refreshToken(request);
        } catch (Exception e) {
            log.warn("[comm-hub] Device Token 续签调用异常 deviceCode={}: {}", deviceCode, e.getMessage());
            return;
        }
        if (result == null || !result.isSuccess() || result.getResult() == null) {
            log.warn("[comm-hub] Device Token 续签失败 deviceCode={}: {}", deviceCode,
                    result == null ? "无响应" : result.getMessage());
            return;
        }

        DeviceTokenRefreshResult refreshed = result.getResult();
        MqttPublishRequest publishRequest = new MqttPublishRequest();
        publishRequest.setDeviceId(deviceCode);
        publishRequest.setTopicSuffix(CommHubTopicConstants.TOPIC_OTA_TOKEN_REFRESH);
        publishRequest.setPayload(Map.of(
                FIELD_DEVICE_TOKEN, refreshed.getDeviceToken(),
                FIELD_TOKEN_EXPIRES_AT, refreshed.getTokenExpiresAt().toString()
        ));
        try {
            mqttPublisher.publish(publishRequest);
        } catch (Exception e) {
            log.warn("[comm-hub] Device Token 续签结果下行发布失败 deviceCode={}: {}", deviceCode, e.getMessage());
        }
    }

    private void handleBridgeAck(String ackTopicSuffix, Map<String, Object> parsed, String rawPayload) {
        for (String commandIdField : ackPendingService.ackCommandIdFields(ackTopicSuffix)) {
            Object commandId = parsed.get(commandIdField);
            if (commandId != null) {
                ackPendingService.complete(commandId.toString(), rawPayload);
                return;
            }
        }
        log.warn("[comm-hub] {} ACK 缺少可识别的指令关联字段，候选字段={}，忽略: {}",
                ackTopicSuffix, ackPendingService.ackCommandIdFields(ackTopicSuffix), rawPayload);
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
