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

/**
 * OTA 升级进度上报处理器（Topic: device/{deviceCode}/ota/progress）
 *
 * <p>设备在 OTA 各阶段均需上报本消息，平台据此维护升级记录状态机：
 * <pre>
 *   NOTIFIED → CONFIRMED → DOWNLOADING → DOWNLOADED → INSTALLING → SUCCESS
 *                                                                 ↘ FAILED
 *   任何阶段超时（定时任务检测）→ TIMEOUT
 * </pre>
 *
 * <p>处理流程：
 * <ol>
 *   <li>解密密文 → 解析为 {@link MqttMessageModel.OtaProgress}</li>
 *   <li>定位升级记录：优先用 recordId 精确查询，降级用 deviceCode 取最近一条</li>
 *   <li>若 downloadedBytes > 0，更新断点续传进度到 Redis（TTL = 超时阈值 + 10min）</li>
 *   <li>更新升级记录各字段（状态、进度、下载字节、失败原因、开始时间）</li>
 *   <li>若状态为终态（SUCCESS/FAILED）：设置结束时间；成功时同步更新设备固件版本</li>
 *   <li>刷新任务统计数据（成功数/失败数/升级中数）</li>
 *   <li>WebSocket 推送 OTA 进度到前端</li>
 *   <li>终态或 CONFIRMED 时记录操作日志</li>
 * </ol>
 *
 * @see org.jeecg.modules.device.service.impl.IotOtaServiceImpl#executeUpgradeTask 触发 OTA 通知的入口
 */
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

    /**
     * 处理 OTA 升级进度上报消息
     *
     * @param deviceCode 设备编号（从 Topic 中提取）
     * @param payload    AES 加密的消息体密文
     * @throws Exception 解密失败或 JSON 解析失败时抛出
     */
    public void handle(String deviceCode, String payload) throws Exception {
        // 1. 解密 + 解析进度消息
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        log.info("[OtaProgressHandler] 解密成功, 设备上报消息体为: {}", decrypted);
        MqttMessageModel.OtaProgress p = objectMapper.readValue(decrypted, MqttMessageModel.OtaProgress.class);
        log.info("[OTA] 设备[{}] status={} progress={}% bytes={}", deviceCode, p.getStatus(), p.getProgress(), p.getDownloadedBytes());

        // 2. 定位升级记录：优先用 recordId 精确查，避免多任务并发时误匹配
        IotOtaUpgradeRecord record = p.getRecordId() != null
                ? recordMapper.selectById(p.getRecordId())
                : recordMapper.selectOne(new LambdaQueryWrapper<IotOtaUpgradeRecord>()
                        .eq(IotOtaUpgradeRecord::getDeviceCode, deviceCode)
                        .orderByDesc(IotOtaUpgradeRecord::getCreateTime).last("LIMIT 1"));
        if (record == null) {
            log.warn("[OTA] 未找到升级记录 deviceCode={}", deviceCode);
            return;
        }

        // 3. 断点续传：将已下载字节数缓存到 Redis，供下次 executeUpgradeTask 时读取并填入 OtaNotify
        if (p.getDownloadedBytes() != null && p.getDownloadedBytes() > 0) {
            redisTemplate.opsForValue().set(
                    DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX + deviceCode + ":" + record.getId(),
                    String.valueOf(p.getDownloadedBytes()),
                    DeviceConstant.Timeout.OTA_UPGRADE_TIMEOUT_MINUTES + 10, TimeUnit.MINUTES);
        }

        // 4. 构造更新对象（只更新有值的字段，避免覆盖历史数据）
        IotOtaUpgradeRecord upd = new IotOtaUpgradeRecord();
        upd.setId(record.getId());
        upd.setUpgradeStatus(p.getStatus());
        if (p.getProgress()        != null) upd.setDownloadProgress(p.getProgress());
        if (p.getDownloadedBytes() != null) upd.setDownloadedBytes(p.getDownloadedBytes());
        if (p.getFailReason()      != null) upd.setFailReason(p.getFailReason());
        // 首次进入 DOWNLOADING 状态时记录开始时间
        if (p.getStatus() == DeviceConstant.OtaUpgradeStatus.DOWNLOADING && record.getStartTime() == null) {
            upd.setStartTime(LocalDateTime.now());
        }

        // 5. 终态处理（SUCCESS 或 FAILED）
        boolean terminal = p.getStatus() == DeviceConstant.OtaUpgradeStatus.SUCCESS
                        || p.getStatus() == DeviceConstant.OtaUpgradeStatus.FAILED;
        if (terminal) {
            upd.setFinishTime(LocalDateTime.now());
            if (p.getStatus() == DeviceConstant.OtaUpgradeStatus.SUCCESS && p.getNewVersion() != null) {
                // 升级成功：同步更新设备固件版本字段
                LambdaUpdateWrapper<IotDevice> updateWrapper = new LambdaUpdateWrapper<IotDevice>()
                        .eq(IotDevice::getDeviceCode, deviceCode)
                        .set(IotDevice::getFirmwareVersion, p.getNewVersion());
                deviceMapper.update(null, updateWrapper);
                // 清除断点续传缓存（升级完成，不再需要）
                redisTemplate.delete(DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX + deviceCode + ":" + record.getId());
            }
        }

        // 6. 持久化升级记录 + 刷新任务统计（成功数/失败数/升级中数）
        recordMapper.updateById(upd);
        taskMapper.refreshTaskStatistics(record.getTaskId());

        // 7. WebSocket 实时推送 OTA 进度到前端
        webSocketServer.pushOtaProgress(deviceCode, p.getTaskId(), p.getStatus(), p.getProgress());

        // 8. 终态或设备确认收到通知时记录操作日志
        if (terminal || p.getStatus() == DeviceConstant.OtaUpgradeStatus.CONFIRMED) {
            logService.recordLog(record.getDeviceId(), deviceCode, DeviceConstant.OperationType.FIRMWARE_UPGRADE,
                    buildDesc(p), null, DeviceConstant.OperationSource.DEVICE,
                    p.getStatus() == DeviceConstant.OtaUpgradeStatus.SUCCESS ? "SUCCESS" : "FAIL",
                    p.getFailReason(), null, null);
        }
    }

    /**
     * 根据 OTA 进度状态构造可读的操作描述
     *
     * @param p OTA 进度消息
     * @return 人读描述文本
     */
    private String buildDesc(MqttMessageModel.OtaProgress p) {
        return switch (p.getStatus()) {
            case DeviceConstant.OtaUpgradeStatus.CONFIRMED -> "设备确认接收OTA通知";
            case DeviceConstant.OtaUpgradeStatus.SUCCESS   -> "OTA升级成功, 版本=" + p.getNewVersion();
            case DeviceConstant.OtaUpgradeStatus.FAILED    -> "OTA升级失败: " + p.getFailReason();
            default -> "OTA进度更新 status=" + p.getStatus();
        };
    }
}
