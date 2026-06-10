package org.jeecg.modules.device.datacollect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.jeecg.modules.device.datacollect.dto.mqtt.StartCollectCmd;
import org.jeecg.modules.device.datacollect.dto.mqtt.StopCollectCmd;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.util.OperationLogDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * 数采 MQTT 指令服务：向机器人下发开始/停止采集、OSS 凭证等指令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("${mqtt.enabled:false} && ${darwin.integration.enabled:false}")
public class DataCollectCommandService {

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final CommandEncryptService encryptService;
    private final IDeviceOperationLogService logService;

    public void sendStartCollect(String deviceCode, String taskId, StartCollectCmd.CollectParams params) {
        StartCollectCmd cmd = StartCollectCmd.builder()
                .timestamp(System.currentTimeMillis())
                .deviceSn(deviceCode)
                .taskId(taskId)
                .params(params)
                .build();
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_START_COLLECT, cmd, "startCollect", taskId);
    }

    public void sendStopCollect(String deviceCode, String taskId, StopCollectCmd.CollectParams params) {
        StopCollectCmd cmd = StopCollectCmd.builder()
                .timestamp(System.currentTimeMillis())
                .deviceSn(deviceCode)
                .taskId(taskId)
                .params(params)
                .build();
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_STOP_COLLECT, cmd, "stopCollect", taskId);
    }

    public void sendCollectUrlResponse(String deviceCode, CollectUrlResponseCmd cmd) {
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_COLLECT_URL_RESP, cmd, "collectUrlResponse", cmd.getRequestId());
    }

    /** OSS 授权失败时通知设备，避免设备盲重试（code=1, params=null） */
    public void sendCollectUrlFailure(String deviceCode, String requestId, String errorMessage) {
        CollectUrlResponseCmd cmd = CollectUrlResponseCmd.builder()
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .deviceSn(deviceCode)
                .code(1)
                .message(errorMessage != null ? errorMessage : "OSS authorization failed")
                .params(null)
                .build();
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_COLLECT_URL_RESP, cmd, "collectUrlResponseFailure", requestId);
    }

    private void publish(String deviceCode, String topicPath, Object payload, String cmdName, String correlationId) {
        String topic = "device/" + deviceCode + "/" + topicPath;
        String detail = correlationId != null && !correlationId.isBlank()
                ? OperationLogDetail.ofRequest(correlationId, topic)
                : OperationLogDetail.ofTopic(topic);
        try {
            String json = objectMapper.writeValueAsString(payload);
            String encrypted = encryptService.encryptForDevice(deviceCode, json);
            mqttPublisher.publishToDevice(deviceCode, topic, encrypted, 1);
            log.info("[DataCollect] 指令已下发 cmd={} deviceCode={}", cmdName, deviceCode);
            String result = "PENDING";
            if (cmdName.contains("Failure")) {
                result = "FAIL";
            } else if ("collectUrlResponse".equals(cmdName)) {
                result = "SUCCESS";
            }
            logService.recordLog(null, deviceCode,
                    DeviceConstant.OperationType.DATA_COLLECT,
                    "平台下发数采指令: " + cmdName,
                    detail,
                    DeviceConstant.OperationSource.PLATFORM, result, null, null, null);
        } catch (Exception e) {
            log.error("[DataCollect] 指令下发失败 cmd={} deviceCode={}", cmdName, deviceCode, e);
            logService.recordLog(null, deviceCode,
                    DeviceConstant.OperationType.DATA_COLLECT,
                    "平台下发数采指令失败: " + cmdName,
                    detail,
                    DeviceConstant.OperationSource.PLATFORM, "FAIL", e.getMessage(), null, null);
            throw new RuntimeException("数采指令下发失败: " + cmdName, e);
        }
    }
}
