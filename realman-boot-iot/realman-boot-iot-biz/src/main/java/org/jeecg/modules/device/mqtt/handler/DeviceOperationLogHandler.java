package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.stereotype.Component;
import java.time.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceOperationLogHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.OperationLogReport r = objectMapper.readValue(decrypted, MqttMessageModel.OperationLogReport.class);
        logService.recordLog(null, deviceCode, r.getOperationType(), r.getOperationDesc(),
                r.getOperationDetail(), DeviceConstant.OperationSource.DEVICE,
                r.getOperationResult(), null, null,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getOperationTime()), ZoneId.systemDefault()));
    }
}
