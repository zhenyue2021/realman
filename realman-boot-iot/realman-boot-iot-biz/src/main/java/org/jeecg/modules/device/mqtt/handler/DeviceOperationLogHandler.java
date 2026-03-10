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

/**
 * 设备操作日志上报处理器（Topic: device/{deviceCode}/log/operation）
 *
 * <p>设备端会主动将自身的关键操作（如本地重启、配置变更、传感器异常等）
 * 通过本 Topic 上报至平台，平台直接持久化到操作日志表。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解密密文 → 解析为 {@link MqttMessageModel.OperationLogReport}</li>
 *   <li>将设备端时间戳（毫秒）转为 LocalDateTime，作为 operationTime</li>
 *   <li>调用日志服务异步写入 DB</li>
 * </ol>
 *
 * <p>与平台侧日志的区别：
 * <ul>
 *   <li>来源标记为 {@link DeviceConstant.OperationSource#DEVICE}（设备主动上报）</li>
 *   <li>operationTime 使用设备端时间（而非平台接收时间）</li>
 *   <li>deviceId 暂为 null（通过 deviceCode 反查，或由日志服务内部补全）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceOperationLogHandler {

    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;

    /**
     * 处理设备上报的操作日志
     *
     * @param deviceCode 设备编号（从 Topic 中提取）
     * @param payload    AES 加密的消息体密文
     * @throws Exception 解密失败或 JSON 解析失败时抛出
     */
    public void handle(String deviceCode, String payload) throws Exception {
        // 1. 解密 + 解析日志消息
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.OperationLogReport r = objectMapper.readValue(decrypted, MqttMessageModel.OperationLogReport.class);

        // 2. 写入日志（operationTime 使用设备端时间，来源标记为 DEVICE）
        logService.recordLog(
                null,                          // deviceId：此处暂为 null，日志服务通过 deviceCode 关联
                deviceCode,
                r.getOperationType(),
                r.getOperationDesc(),
                r.getOperationDetail(),
                DeviceConstant.OperationSource.DEVICE,
                r.getOperationResult(),
                null,                          // failReason
                null,                          // operator（设备端无操作人）
                // 将设备端毫秒时间戳转为 LocalDateTime（使用系统时区）
                LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getOperationTime()), ZoneId.systemDefault())
        );
    }
}
