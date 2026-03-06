package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.*;
import org.jeecg.modules.device.mapper.*;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OtaProgressHandler {

    private final IotDeviceMapper            deviceMapper;
    private final IotOtaUpgradeRecordMapper  recordMapper;
    private final IotOtaUpgradeTaskMapper    taskMapper;
    private final CommandEncryptService      encryptService;
    private final ObjectMapper               objectMapper;
    private final StringRedisTemplate        redisTemplate;
    private final DeviceWebSocketServer      webSocketServer;
    private final IDeviceOperationLogService logService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.OtaProgress p = objectMapper.readValue(decrypted, MqttMessageModel.OtaProgress.class);
        log.info("[OTA] 设备[{}] status={} progress={}% bytes={}", deviceCode, p.getStatus(), p.getProgress(), p.getDownloadedBytes());

        IotOtaUpgradeRecord record = p.getRecordId() != null ? recordMapper.selectById(p.getRecordId())
                : recordMapper.selectOne(new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .eq(IotOtaUpgradeRecord::getDeviceCode, deviceCode)
                        .orderByDesc(IotOtaUpgradeRecord::getCreateTime).last("LIMIT 1"));
        if (record == null) { log.warn("[OTA] 未找到升级记录 deviceCode={}", deviceCode); return; }

        // 断点续传：保存已下载字节数
        if (p.getDownloadedBytes() != null && p.getDownloadedBytes() > 0) {
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX + deviceCode + ":" + record.getId(),
                    String.valueOf(p.getDownloadedBytes()),
                    DeviceConstant.Timeout.OTA_UPGRADE_TIMEOUT_MINUTES + 10, TimeUnit.MINUTES);
        }

        IotOtaUpgradeRecord upd = new IotOtaUpgradeRecord();
        upd.setId(record.getId());
        upd.setUpgradeStatus(p.getStatus());
        if (p.getProgress()      != null) upd.setDownloadProgress(p.getProgress());
        if (p.getDownloadedBytes() != null) upd.setDownloadedBytes(p.getDownloadedBytes());
        if (p.getFailReason()    != null) upd.setFailReason(p.getFailReason());
        if (p.getStatus() == DeviceConstant.OtaUpgradeStatus.DOWNLOADING && record.getStartTime() == null)
            upd.setStartTime(LocalDateTime.now());

        boolean terminal = p.getStatus() == DeviceConstant.OtaUpgradeStatus.SUCCESS
                        || p.getStatus() == DeviceConstant.OtaUpgradeStatus.FAILED;
        if (terminal) {
            upd.setFinishTime(LocalDateTime.now());
            if (p.getStatus() == DeviceConstant.OtaUpgradeStatus.SUCCESS && p.getNewVersion() != null) {
                deviceMapper.update(null, new LambdaUpdateWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, deviceCode)
                        .set(IotDevice::getFirmwareVersion, p.getNewVersion()));
                redisTemplate.delete(DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX + deviceCode + ":" + record.getId());
            }
        }
        recordMapper.updateById(upd);
        taskMapper.refreshTaskStatistics(record.getTaskId());
        webSocketServer.pushOtaProgress(deviceCode, p.getTaskId(), p.getStatus(), p.getProgress());

        if (terminal || p.getStatus() == DeviceConstant.OtaUpgradeStatus.CONFIRMED) {
            logService.recordLog(record.getDeviceId(), deviceCode, DeviceConstant.OperationType.FIRMWARE_UPGRADE,
                    buildDesc(p), null, DeviceConstant.OperationSource.DEVICE,
                    p.getStatus() == DeviceConstant.OtaUpgradeStatus.SUCCESS ? "SUCCESS" : "FAIL",
                    p.getFailReason(), null, null);
        }
    }

    private String buildDesc(MqttMessageModel.OtaProgress p) {
        return switch (p.getStatus()) {
            case DeviceConstant.OtaUpgradeStatus.CONFIRMED -> "设备确认接收OTA通知";
            case DeviceConstant.OtaUpgradeStatus.SUCCESS   -> "OTA升级成功, 版本=" + p.getNewVersion();
            case DeviceConstant.OtaUpgradeStatus.FAILED    -> "OTA升级失败: " + p.getFailReason();
            default -> "OTA进度更新 status=" + p.getStatus();
        };
    }
}
