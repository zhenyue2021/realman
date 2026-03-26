package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceStatusMapper;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 机器人/主控设备原始状态上报处理器
 *
 * <p>Topic 格式：{deviceCode}/slave/states（机器人） | {deviceCode}/master/states（主控）
 *
 * <p>处理流程：解密 → 校验设备 → WebSocket 推送 → 缓冲最新状态
 *
 * <p>持久化策略：每台机器人设备的最新状态缓冲在内存中，由外部调度器（{@code DeviceSchedulerJob}）
 * 定期调用 {@link #flushPending()} 统一落库，避免高频上报（约1次/秒）对数据库造成写压力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
public class RobotSlaveStatusHandler {

    private final DeviceWebSocketServer deviceWebSocketServer;
    private final ObjectMapper objectMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceStatusMapper statusMapper;
    private final CommandEncryptService encryptService;

    /**
     * 待落库缓冲区：key=deviceCode，value=该设备最新一条解密后的状态 JSON。
     * 每次有新上报时覆盖旧值，确保定时任务刷写的始终是最新状态。
     */
    private final ConcurrentHashMap<String, PendingEntry> pendingMap = new ConcurrentHashMap<>();

    /** 待落库条目：保存设备实体 + 最新原始 JSON */
    private record PendingEntry(IotDevice device, String raw) {}

    // -------------------------------------------------------------------------
    // 公开入口
    // -------------------------------------------------------------------------

    /** 处理机器人原始状态上报（{robotCode}/slave/states） */
    public void handle(String robotCode, String payload) {
        log.debug("[SlaveStatusHandler] slave上报 robotCode={}", robotCode);
        processStatus(robotCode, payload, deviceWebSocketServer::pushRobotStatus, true);
    }

    /** 处理主控设备原始状态上报（{masterCode}/master/states） */
    public void handleMasterStatus(String masterCode, String payload) {
        log.debug("[SlaveStatusHandler] master上报 masterCode={}", masterCode);
        processStatus(masterCode, payload, deviceWebSocketServer::pushMasterStatus, false);
    }

    // -------------------------------------------------------------------------
    // 内部逻辑
    // -------------------------------------------------------------------------

    /**
     * 通用状态处理：解密 → 校验设备 → WebSocket 推送 → 可选缓冲
     *
     * @param deviceCode 设备编码
     * @param payload    原始 Payload（可能加密）
     * @param wsPusher   WebSocket 推送方法引用
     * @param buffer     是否将最新状态缓入待落库缓冲区
     */
    private void processStatus(String deviceCode, String payload,
                                BiConsumer<String, String> wsPusher, boolean buffer) {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);

        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .last("LIMIT 1"));
        if (device == null) {
            log.warn("[SlaveStatusHandler] 未知设备，忽略上报: {}", deviceCode);
            return;
        }

        wsPusher.accept(deviceCode, decrypted);

        if (buffer) {
            // 覆盖写：高频上报时始终保留最新状态，等待定时任务统一落库
            pendingMap.put(deviceCode, new PendingEntry(device, decrypted));
        }
    }

    /**
     * 定时落库：每分钟将缓冲区中所有设备的最新状态写入 DB，每台设备落一条记录。
     *
     * <p>采用"先摘出、再写库"策略：
     * <ol>
     *   <li>从 {@link #pendingMap} 中原子摘出所有待写条目</li>
     *   <li>逐条写库（失败单独打印日志，不影响其他设备）</li>
     * </ol>
     * 摘出后若设备继续上报，新条目会重新进入 pendingMap，等下一分钟刷写，不会丢失。
     */
    public void flushPending() {
        if (pendingMap.isEmpty()) {
            return;
        }
        // 原子摘出：从 map 中移除并收集本轮需要落库的条目
        List<PendingEntry> batch = new ArrayList<>();
        for (Map.Entry<String, PendingEntry> entry : pendingMap.entrySet()) {
            PendingEntry removed = pendingMap.remove(entry.getKey());
            if (removed != null) {
                batch.add(removed);
            }
        }
        log.info("[SlaveStatusHandler] 定时落库，本轮设备数={}", batch.size());
        for (PendingEntry e : batch) {
            try {
                doPersist(e.device(), e.raw());
            } catch (Exception ex) {
                log.error("[SlaveStatusHandler] 落库失败 deviceCode={}", e.device().getDeviceCode(), ex);
            }
        }
    }

    /**
     * 执行单条状态记录写库。
     *
     * <p>reportTime 取设备端 timestamp 字段（毫秒）；字段缺失或值 ≤0 时降级为平台当前时间，
     * 避免写入 epoch（1970-01-01）。
     */
    private void doPersist(IotDevice device, String raw) {

        IotDeviceStatus s = new IotDeviceStatus();
        s.setDeviceId(device.getId());
        s.setDeviceCode(device.getDeviceCode());
        s.setRawData(raw);
        long timestamp = 0;
        try {
            JsonNode root = objectMapper.readTree(raw);
            timestamp = root.path("timestamp").asLong();
        } catch (Exception e) {
            log.warn("[SlaveStatusHandler] 解析 timestamp 失败，使用平台时间: deviceCode={}", device.getDeviceCode());
        }

        LocalDateTime reportTime = timestamp > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                : LocalDateTime.now();
        s.setReportTime(reportTime);
        s.setReceiveTime(LocalDateTime.now());
        statusMapper.insert(s);
    }
}
