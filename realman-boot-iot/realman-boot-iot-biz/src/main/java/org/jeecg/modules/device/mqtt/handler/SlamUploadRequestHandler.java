package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IIotSlamService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlamUploadRequestHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final IIotSlamService slamService;
    private final MqttPublisher mqttPublisher;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.SlamUploadRequest req =
                objectMapper.readValue(decrypted, MqttMessageModel.SlamUploadRequest.class);
        MqttMessageModel.SlamUploadPermit permit = slamService.handleUploadRequest(deviceCode, req);
        mqttPublisher.publishToDevice(deviceCode,
                String.format(DeviceConstant.MqttTopic.SLAM_UPLOAD_PERMIT, deviceCode),
                objectMapper.writeValueAsString(permit), 1);
        log.info("[SLAM] 上传许可已下发 deviceCode={}, mapId={}", deviceCode, permit.getMapId());
    }
}

