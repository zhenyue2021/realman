package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IIotSlamCommandService;
import org.springframework.stereotype.Component;

/**
 * 处理设备响应建图/定位/导航指令（上行）
 *
 * <p>Topic：device/{deviceCode}/slam/ack
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlamAckHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final IIotSlamCommandService slamCommandService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.SlamAck ack = objectMapper.readValue(decrypted, MqttMessageModel.SlamAck.class);
        log.info("[SlamAck] 收到响应: deviceCode={}, commandId={}, function={}, sequence={}/{}, success={}",
                deviceCode, ack.getCommandId(), ack.getFunction(), ack.getSequence(), ack.getTotal(), ack.getSuccess());
        slamCommandService.handleAck(deviceCode, ack);
    }
}
