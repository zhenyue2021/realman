package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IIotSlamService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlamSyncAckHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final IIotSlamService slamService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.SlamSyncAck ack = objectMapper.readValue(decrypted, MqttMessageModel.SlamSyncAck.class);
        slamService.handleSyncAck(deviceCode, ack);
        log.info("[SLAM] 同步ACK已处理 deviceCode={}, taskId={}, bindingId={}, code={}",
                deviceCode, ack.getTaskId(), ack.getBindingId(), ack.getCode());
    }
}

