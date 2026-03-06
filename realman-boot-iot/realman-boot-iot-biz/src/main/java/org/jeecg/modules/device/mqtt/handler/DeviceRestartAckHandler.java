package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceRestartAckHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.RestartAck ack = objectMapper.readValue(decrypted, MqttMessageModel.RestartAck.class);
        log.info("[RestartAck] 设备[{}] code={}", deviceCode, ack.getCode());
        logService.recordLog(null, deviceCode, DeviceConstant.OperationType.REMOTE_RESTART,
                "设备收到重启指令" + (ack.getCode() == 0 ? "并执行" : "失败"),
                "{commandId:" + ack.getCommandId() + "}",
                DeviceConstant.OperationSource.DEVICE,
                ack.getCode() == 0 ? "SUCCESS" : "FAIL",
                ack.getMessage(), null, null);
    }
}
