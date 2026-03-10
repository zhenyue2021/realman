package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.stereotype.Component;

/**
 * 设备重启指令执行确认处理器（Topic: device/{deviceCode}/command/restart/ack）
 *
 * <p>当平台通过 {@code device/{deviceCode}/command/restart} 下发重启指令后，
 * 设备在执行前（或拒绝执行时）通过本 Topic 回复确认消息。
 *
 * <p>典型场景：
 * <ul>
 *   <li>code=0：设备已接收指令，即将执行重启（此后设备断线属正常现象）</li>
 *   <li>code≠0：设备拒绝重启（如正在 OTA 升级中），message 中说明原因</li>
 * </ul>
 *
 * <p>处理流程：
 * <ol>
 *   <li>解密密文 → 解析为 {@link MqttMessageModel.RestartAck}</li>
 *   <li>记录操作日志（含 commandId、执行结果和原因）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceRestartAckHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;

    /**
     * 处理设备重启确认消息
     *
     * @param deviceCode 设备编号（从 Topic 中提取）
     * @param payload    AES 加密的消息体密文
     * @throws Exception 解密失败或 JSON 解析失败时抛出
     */
    public void handle(String deviceCode, String payload) throws Exception {
        // 1. 解密 + 解析确认消息
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.RestartAck ack = objectMapper.readValue(decrypted, MqttMessageModel.RestartAck.class);
        log.info("[RestartAck] 设备[{}] commandId={} code={}", deviceCode, ack.getCommandId(), ack.getCode());

        // 2. 记录操作日志，将重启结果追加到日志链中（与平台侧 PENDING 记录形成完整记录）
        logService.recordLog(null, deviceCode, DeviceConstant.OperationType.REMOTE_RESTART,
                "设备收到重启指令" + (ack.getCode() == 0 ? "并执行" : "失败"),
                "{commandId:" + ack.getCommandId() + "}",
                DeviceConstant.OperationSource.DEVICE,
                ack.getCode() == 0 ? "SUCCESS" : "FAIL",
                ack.getMessage(), null, null);
    }
}
