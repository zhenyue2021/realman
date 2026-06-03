package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.WebRtcAckPendingService;
import org.jeecg.modules.device.util.OperationLogDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * WebRTC 指令 ACK 处理器（Topic: webrtc/{deviceCode}/command/ack）
 *
 * <p>机器人执行 WebRTC 指令后上报本 ACK，通过 payload 中的 {@code command} 字段区分：
 * <ul>
 *   <li>start ACK：通过 {@link WebRtcAckPendingService} 通知等待线程</li>
 *   <li>stop ACK：仅记录日志，不需要等待</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class WebRtcAckHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final WebRtcAckPendingService pendingService;
    private final IIotDeviceCommandRecordService commandRecordService;
    private final IDeviceOperationLogService logService;

    /**
     * 处理 WebRTC ACK
     *
     * @param deviceCode 机器人设备编码
     * @param payload    AES 加密的 Payload
     */
    public void handle(String deviceCode, String payload) {
        try {
            String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
            MqttMessageModel.WebRtcAck ack = objectMapper.readValue(decrypted, MqttMessageModel.WebRtcAck.class);
            log.info("[WebRtcAck] device={} command={} commandId={} success={} message={}",
                    deviceCode, ack.getCommand(), ack.getCommandId(), ack.isSuccess(), ack.getMessage());

            if (ack.getCommandId() != null) {
                pendingService.complete(ack.getCommandId(), ack);
                commandRecordService.ack(ack.getCommandId(), ack.isSuccess(), ack.getMessage(), decrypted);
                String ackTopic = "device/" + deviceCode + "/webrtc/ack";
                logService.recordLog(null, deviceCode,
                        DeviceConstant.OperationType.WEBRTC,
                        "设备 WebRTC " + (ack.getCommand() != null ? ack.getCommand() : "") + " ACK"
                                + (ack.isSuccess() ? "成功" : "失败"),
                        OperationLogDetail.ofCommand(ack.getCommandId(), ackTopic),
                        DeviceConstant.OperationSource.DEVICE,
                        ack.isSuccess() ? "SUCCESS" : "FAIL",
                        ack.isSuccess() ? null : ack.getMessage(),
                        null, null);
            }
        } catch (Exception e) {
            log.error("[WebRtcAck] 处理异常 deviceCode={}", deviceCode, e);
        }
    }
}
