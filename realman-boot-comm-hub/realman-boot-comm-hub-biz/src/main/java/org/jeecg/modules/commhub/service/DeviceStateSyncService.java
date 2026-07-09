package org.jeecg.modules.commhub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceHeartbeatSnapshotRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOccupancyEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOnlineEventRequest;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyState;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 在线/离线/心跳/占用状态同步到设备信息基础服务（SSOT）。取代
 * {@code realman-boot-iot} 现状 {@code DeviceOnlineOfflineHandler} 直接读写
 * {@code iot_device} 表的做法，改为统一经 {@link DeviceInfoFeignClient} 写入，
 * 见设备基座详细设计 2.2、设备通信中台详细设计 6。
 *
 * <p>已知限制：{@code device_info.online_status} 相关写入按每条消息各查一次
 * deviceCode-&gt;deviceId 再回写，未做本地缓存；设备基座详细设计 2.3 规划的
 * SSOT 侧只读缓存落地后可显著降低这里的 Feign 调用量，本轮不做该优化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceStateSyncService {

    private final DeviceInfoFeignClient deviceInfoFeignClient;

    public void handleConnected(String deviceCode) {
        withDeviceId(deviceCode, deviceId -> {
            DeviceOnlineEventRequest request = new DeviceOnlineEventRequest();
            request.setDeviceId(deviceId);
            request.setEventType(OnlineStatus.ONLINE);
            request.setOccurredAt(LocalDateTime.now());
            Result<Void> result = deviceInfoFeignClient.reportOnlineEvent(request);
            logIfFailed("online-event", deviceCode, result);
        });
    }

    public void handleDisconnected(String deviceCode, String offlineReason) {
        withDeviceId(deviceCode, deviceId -> {
            DeviceOnlineEventRequest request = new DeviceOnlineEventRequest();
            request.setDeviceId(deviceId);
            request.setEventType(OnlineStatus.OFFLINE);
            request.setOccurredAt(LocalDateTime.now());
            request.setOfflineReason(offlineReason);
            Result<Void> result = deviceInfoFeignClient.reportOnlineEvent(request);
            logIfFailed("online-event", deviceCode, result);
        });
    }

    /**
     * 处理 {@code device/{code}/status/report} 上行报文。约定 payload 可选携带
     * {@code ipAddress}/{@code occupancyState}/{@code occupancyDetail}/
     * {@code resourceSnapshot} 字段（见设备通信中台详细设计 5.2 映射表）。
     */
    public void handleStatusReport(String deviceCode, Map<String, Object> payload) {
        withDeviceId(deviceCode, deviceId -> {
            DeviceHeartbeatSnapshotRequest heartbeat = new DeviceHeartbeatSnapshotRequest();
            heartbeat.setDeviceId(deviceId);
            heartbeat.setHeartbeatAt(LocalDateTime.now());
            heartbeat.setIpAddress(stringField(payload, "ipAddress"));
            Object resourceSnapshot = payload.get("resourceSnapshot");
            if (resourceSnapshot instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> snapshot = (Map<String, Object>) resourceSnapshot;
                heartbeat.setResourceSnapshot(snapshot);
            }
            Result<Void> result = deviceInfoFeignClient.reportHeartbeatSnapshot(heartbeat);
            logIfFailed("heartbeat-snapshot", deviceCode, result);

            String occupancyStateStr = stringField(payload, "occupancyState");
            if (StringUtils.hasText(occupancyStateStr)) {
                DeviceOccupancyEventRequest occupancy = new DeviceOccupancyEventRequest();
                occupancy.setDeviceId(deviceId);
                occupancy.setOccupancyState(OccupancyState.valueOf(occupancyStateStr));
                String detailStr = stringField(payload, "occupancyDetail");
                if (StringUtils.hasText(detailStr)) {
                    occupancy.setOccupancyDetail(OccupancyDetail.valueOf(detailStr));
                }
                occupancy.setOccurredAt(LocalDateTime.now());
                Result<Void> occupancyResult = deviceInfoFeignClient.reportOccupancyEvent(occupancy);
                logIfFailed("occupancy-event", deviceCode, occupancyResult);
            }
        });
    }

    /** 供内部按 deviceCode 解析 deviceId 使用，返回 null 表示设备未注册或 SSOT 不可用。 */
    public String resolveDeviceId(String deviceCode) {
        try {
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDeviceByCode(deviceCode);
            return result != null && result.isSuccess() && result.getResult() != null
                    ? result.getResult().getDeviceId() : null;
        } catch (Exception e) {
            log.debug("[comm-hub] 设备信息基础服务查询未命中或不可用 deviceCode={}: {}", deviceCode, e.getMessage());
            return null;
        }
    }

    public DeviceInfoDTO resolveDevice(String deviceCode) {
        try {
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDeviceByCode(deviceCode);
            return result != null && result.isSuccess() ? result.getResult() : null;
        } catch (Exception e) {
            log.debug("[comm-hub] 设备信息基础服务查询未命中或不可用 deviceCode={}: {}", deviceCode, e.getMessage());
            return null;
        }
    }

    private void withDeviceId(String deviceCode, java.util.function.Consumer<String> action) {
        String deviceId = resolveDeviceId(deviceCode);
        if (deviceId == null) {
            log.warn("[comm-hub] 未找到设备，跳过状态同步 deviceCode={}", deviceCode);
            return;
        }
        action.accept(deviceId);
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private void logIfFailed(String action, String deviceCode, Result<Void> result) {
        if (result == null || !result.isSuccess()) {
            log.warn("[comm-hub] {} 同步 SSOT 失败 deviceCode={}", action, deviceCode);
        }
    }
}
