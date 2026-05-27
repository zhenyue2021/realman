package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.handler.CollectUrlRequestHandler;
import org.jeecg.modules.device.datacollect.handler.OssAddressReportHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * device/{deviceCode}/{path} 与 {deviceCode}/master|slave/{path} Topic 路由表。
 *
 * <p>从 {@link MqttMessageDispatcher} 抽离，便于扩展 Topic 映射与单元测试。
 * 与 {@link MqttMessageDispatcher} 相同，仅在 {@code mqtt.enabled=true} 时装配。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MqttDeviceTopicRouter {

    private final DeviceStatusHandler statusHandler;
    private final DeviceConfigAckHandler configAckHandler;
    private final DeviceCommandAckHandler commandAckHandler;
    private final OtaProgressHandler otaProgressHandler;
    private final DeviceOperationLogHandler operationLogHandler;
    private final DeviceCameraStreamResponseHandler deviceCameraStreamResponseHandler;
    private final MasterAssociatedDeviceResponseHandler masterAssociatedDeviceResponseHandler;
    private final RobotSlaveStatusHandler robotSlaveStatusHandler;
    private final SlamAckHandler slamAckHandler;
    private final SlamStatesHandler slamStatesHandler;
    private final ExtParamsRequestHandler extParamsRequestHandler;
    private final WebRtcAckHandler webRtcAckHandler;
    private final WebRtcRestartHandler webRtcRestartHandler;
    private final OssAddressReportHandler ossAddressReportHandler;
    private final DeviceOnlineReportHandler deviceOnlineReportHandler;
    private final MasterCommandHandler masterCommandHandler;

    @Autowired(required = false)
    private CollectUrlRequestHandler collectUrlRequestHandler;

    /**
     * 路由 device/{deviceCode}/{path}
     */
    public void routeDeviceTopic(String deviceCode, String path, String topic, String payload) throws Exception {
        if (path.startsWith("command/") && path.endsWith("/ack")) {
            String cmd = path.substring("command/".length(), path.length() - "/ack".length());
            if (cmd.contains("/")) {
                log.warn("[TopicRouter] 未知指令ACK路径: {}", topic);
                return;
            }
            commandAckHandler.handle(deviceCode, cmd, payload);
            return;
        }

        switch (path) {
            case "status/report" -> statusHandler.refreshPresence(deviceCode, payload);
            case "config/ack" -> configAckHandler.handle(deviceCode, payload);
            case "ota/progress" -> otaProgressHandler.handle(deviceCode, payload);
            case "log/operation" -> operationLogHandler.handle(deviceCode, payload);
            case "camera/stream/ack" -> deviceCameraStreamResponseHandler.handle(deviceCode, payload);
            case "teleop/associated-device/ack" -> masterAssociatedDeviceResponseHandler.handle(deviceCode, payload);
            case "slam/ack" -> slamAckHandler.handle(deviceCode, payload);
            case "slam/states" -> slamStatesHandler.handle(deviceCode, payload);
            case "ext-params/request" -> extParamsRequestHandler.handle(deviceCode, payload);
            case "webrtc/ack" -> webRtcAckHandler.handle(deviceCode, payload);
            case "webrtc/restart" -> webRtcRestartHandler.handle(deviceCode, payload);
            case DataCollectConstant.MQTT_UP_COLLECT_URL_REQUEST -> {
                if (collectUrlRequestHandler != null) {
                    collectUrlRequestHandler.handle(deviceCode, payload);
                } else {
                    log.warn("[TopicRouter] Darwin 集成未启用，忽略 collectUrlRequest deviceCode={}", deviceCode);
                }
            }
            case DataCollectConstant.MQTT_UP_OSS_ADDRESS_REPORT -> ossAddressReportHandler.handle(deviceCode, payload);
            case DataCollectConstant.MQTT_UP_DEVICE_ONLINE -> deviceOnlineReportHandler.handle(deviceCode, payload);
            default -> log.warn("[TopicRouter] 未知路径: {}", topic);
        }
    }

    /**
     * 路由 {deviceCode}/master/{path} 或 {deviceCode}/slave/{path}
     */
    public void routeRawTopic(String deviceCode, String role, String path, String payload) {
        if ("slave".equals(role) && "states".equals(path)) {
            robotSlaveStatusHandler.handle(deviceCode, payload);
            return;
        }
        if ("master".equals(role) && "states".equals(path)) {
            robotSlaveStatusHandler.handleMasterStatus(deviceCode, payload);
            return;
        }
        if ("master".equals(role) && "cmd".equals(path)) {
            masterCommandHandler.handle(deviceCode, payload);
            return;
        }
        log.debug("[TopicRouter] 原始上报 deviceCode={} role={} path={}", deviceCode, role, path);
    }
}
