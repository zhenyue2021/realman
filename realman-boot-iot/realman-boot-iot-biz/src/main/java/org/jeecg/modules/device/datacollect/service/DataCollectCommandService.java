package org.jeecg.modules.device.datacollect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.jeecg.modules.device.datacollect.dto.mqtt.StartCollectCmd;
import org.jeecg.modules.device.datacollect.dto.mqtt.StopCollectCmd;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.springframework.stereotype.Service;

/**
 * 数采 MQTT 指令服务：向机器人下发开始/停止采集、OSS 凭证等指令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataCollectCommandService {

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    public void sendStartCollect(String deviceCode, String taskId, StartCollectCmd.CollectParams params) {
        StartCollectCmd cmd = StartCollectCmd.builder()
                .timestamp(System.currentTimeMillis())
                .deviceSn(deviceCode)
                .taskId(taskId)
                .params(params)
                .build();
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_START_COLLECT, cmd, "startCollect");
    }

    public void sendStopCollect(String deviceCode, String taskId, StopCollectCmd.CollectParams params) {
        StopCollectCmd cmd = StopCollectCmd.builder()
                .timestamp(System.currentTimeMillis())
                .deviceSn(deviceCode)
                .taskId(taskId)
                .params(params)
                .build();
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_STOP_COLLECT, cmd, "stopCollect");
    }

    public void sendCollectUrlResponse(String deviceCode, CollectUrlResponseCmd cmd) {
        publish(deviceCode, DataCollectConstant.MQTT_DOWN_COLLECT_URL_RESP, cmd, "collectUrlResponse");
    }

    private void publish(String deviceCode, String topicPath, Object payload, String cmdName) {
        String topic = "device/" + deviceCode + "/" + topicPath;
        try {
            String json = objectMapper.writeValueAsString(payload);
            mqttPublisher.publishToDevice(deviceCode, topic, json, 1);
            log.info("[DataCollect] 指令已下发 cmd={} deviceCode={}", cmdName, deviceCode);
        } catch (Exception e) {
            log.error("[DataCollect] 指令下发失败 cmd={} deviceCode={}", cmdName, deviceCode, e);
            throw new RuntimeException("数采指令下发失败: " + cmdName, e);
        }
    }
}
