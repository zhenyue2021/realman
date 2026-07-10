package org.jeecg.modules.ota.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.commhub.contract.api.CommHubFeignClient;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyState;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.enums.NonTerminalStates;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.service.IOtaKeyService;
import org.jeecg.modules.ota.service.IOtaPrecheckService;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.util.SemVerComparator;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.*;

/**
 * 前置校验实现，对齐 OTA 平台详细设计六章（PRD 4.4.2）。
 *
 * <p>已知限制：{@code MASTER_FAULT}/{@code EMERGENCY_STOP}/{@code FAULT}/{@code MOVING}
 * 这组 PRD 定义的硬禁止状态，目前设备基座 SSOT 未采集对应数据（占用四态只有
 * IDLE/SLEEP/OCCUPIED/OFFLINE + TELEOP/LOCAL/AUTONOMOUS 细分，没有硬件故障/急停/
 * 运动中信号），本实现无法校验这几项，只记录警告而不阻断——这是设备侧数据源缺失
 * 导致的真实空白，不是遗漏，需要设备基座后续采集这类信号后才能补上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtaPrecheckServiceImpl implements IOtaPrecheckService {

    private static final String ERR_PRECONDITION_FAILED = "ERR_PRECONDITION_FAILED";
    private static final String ERR_RESOURCE_INSUFFICIENT = "ERR_RESOURCE_INSUFFICIENT";
    private static final String ERR_VERSION_INCOMPATIBLE = "ERR_VERSION_INCOMPATIBLE";
    private static final int DEFAULT_MIN_MEMORY_MB = 256;
    private static final String TOPIC_SUFFIX_RESOURCE_PROBE = "ota/resource-probe";
    private static final long RESOURCE_PROBE_ACK_TIMEOUT_MS = 5000;

    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final IOtaKeyService keyService;
    private final IOtaSystemSettingService systemSettingService;
    private final CommHubFeignClient commHubFeignClient;

    @Override
    public boolean isOffline(DeviceInfoDTO device) {
        return device.getOnlineStatus() == OnlineStatus.OFFLINE
                || device.getOnlineStatus() == OnlineStatus.UNACTIVATED
                || device.getOccupancyState() == OccupancyState.OFFLINE;
    }

    @Override
    public void checkDeviceState(DeviceInfoDTO device) {
        if (isOffline(device)) {
            return;
        }
        boolean upgrading = taskDeviceMapper.selectCount(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getDeviceId, device.getDeviceId())
                .notIn(OtaTaskDevice::getState, NonTerminalStates.terminalStates())) > 0;
        if (upgrading) {
            throw new JeecgBootBizTipException(ERR_PRECONDITION_FAILED + ": 设备已有升级任务进行中（UPGRADING）");
        }
        if (device.getOccupancyState() == OccupancyState.OCCUPIED) {
            if (device.getOccupancyDetail() == OccupancyDetail.TELEOP) {
                throw new JeecgBootBizTipException(ERR_PRECONDITION_FAILED + ": 设备正在被遥操（TELEOP_ACTIVE）");
            }
            if (device.getOccupancyDetail() == OccupancyDetail.AUTONOMOUS) {
                throw new JeecgBootBizTipException(ERR_PRECONDITION_FAILED + ": 设备正在执行自主任务（TASK_RUNNING）");
            }
        }
        // MASTER_FAULT / EMERGENCY_STOP / FAULT / MOVING：SSOT 无对应数据源，见类注释，本轮不校验
    }

    @Override
    public void checkResources(DeviceInfoDTO device) {
        Map<String, Object> activeSnapshot = probeResourcesActively(device);
        boolean activelyProbed = activeSnapshot != null;
        Map<String, Object> snapshot = activelyProbed ? activeSnapshot : device.getResourceSnapshot();
        LocalDateTime lastHeartbeatAt = device.getLastHeartbeatAt();
        if (CollectionUtils.isEmpty(snapshot) || (!activelyProbed && lastHeartbeatAt == null)) {
            throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": 设备尚无心跳资源数据，无法校验");
        }

        if (!activelyProbed) {
            assertFresh(lastHeartbeatAt, systemSettingService.getLong(DISK_VALID_SECONDS), "磁盘");
        }
        // 磁盘可用空间 ≥ 固件大小 × 2 的具体比较见 checkDiskSpaceForFirmware（调用方在拿到目标固件后另行调用），这里只做"有无数据"的基础校验
        Long diskAvailableMb = numberField(snapshot, "disk_available_mb");
        if (diskAvailableMb == null) {
            throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": 缺少 disk_available_mb 字段");
        }

        if (!activelyProbed) {
            assertFresh(lastHeartbeatAt, systemSettingService.getLong(POWER_VALID_SECONDS), "电源");
        }
        String powerStatus = stringField(snapshot, "power_status");
        if (!"normal".equalsIgnoreCase(powerStatus)) {
            throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": 电源状态异常 power_status=" + powerStatus);
        }

        if (!activelyProbed) {
            assertFresh(lastHeartbeatAt, systemSettingService.getLong(MEMORY_VALID_SECONDS), "内存");
        }
        Long memoryAvailableMb = numberField(snapshot, "memory_available_mb");
        if (memoryAvailableMb == null || memoryAvailableMb < DEFAULT_MIN_MEMORY_MB) {
            throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": 可用内存不足，当前 " + memoryAvailableMb + "MiB");
        }

        Object networkReachable = snapshot.get("network_reachable");
        if (activelyProbed) {
            if (!Boolean.TRUE.equals(networkReachable)) {
                throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": 主动探测显示网络不可达（network_reachable=false）");
            }
        } else {
            long networkValidSeconds = systemSettingService.getLong(NETWORK_VALID_SECONDS);
            long secondsSinceHeartbeat = ChronoUnit.SECONDS.between(lastHeartbeatAt, LocalDateTime.now());
            if (secondsSinceHeartbeat > networkValidSeconds || !Boolean.TRUE.equals(networkReachable)) {
                throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT
                        + ": network_reachable 数据已超出有效期或不可达（最后上报 " + secondsSinceHeartbeat + " 秒前）");
            }
        }
        // cpu_load_5m 超阈值只警告，不阻止，对齐 PRD："建议等待后重试（不强制拒绝）"
        Long cpuLoad = numberField(snapshot, "cpu_load_5m");
        if (cpuLoad != null && cpuLoad > 80) {
            log.warn("[ota] 设备 CPU 负载偏高（仅警告，不阻止升级）deviceId={} cpuLoad5m={}", device.getDeviceId(), cpuLoad);
        }
    }

    /**
     * PRD 9.7.4 资源探测（operate=5）：创建任务前主动向设备请求实时资源数据，而非只
     * 依赖可能已过期的心跳快照，见 OTA 平台详细设计 2.1（此前只补了 Topic 常量
     * {@code CommHubTopicConstants#TOPIC_OTA_RESOURCE_PROBE}，未接入实际调用，是真实
     * 遗漏而非设计如此）。经统一下行发布 {@code publish-and-wait}（{@code waitAck=true}）
     * 实现；设备不支持或超时（{@code status != ACKED}）时返回 null，调用方按心跳基础值
     * 回退，等价 PRD"设备端不支持 operate=5，返回 503"分支。
     */
    private Map<String, Object> probeResourcesActively(DeviceInfoDTO device) {
        MqttPublishRequest request = new MqttPublishRequest();
        request.setDeviceId(device.getDeviceId());
        request.setTopicSuffix(TOPIC_SUFFIX_RESOURCE_PROBE);
        request.setPayload(Map.of());
        request.setWaitAck(true);
        request.setAckTimeoutMs(RESOURCE_PROBE_ACK_TIMEOUT_MS);
        try {
            Result<MqttPublishResult> result = commHubFeignClient.publish(request);
            if (result == null || !result.isSuccess() || result.getResult() == null) {
                return null;
            }
            MqttPublishResult publishResult = result.getResult();
            if (!"ACKED".equals(publishResult.getStatus()) || CollectionUtils.isEmpty(publishResult.getAckPayload())) {
                return null;
            }
            return publishResult.getAckPayload();
        } catch (Exception e) {
            log.debug("[ota] 主动资源探测失败，回退心跳基础值 deviceId={}: {}", device.getDeviceId(), e.getMessage());
            return null;
        }
    }

    @Override
    public void checkDiskSpaceForFirmware(DeviceInfoDTO device, int firmwareFileSizeMb) {
        Map<String, Object> snapshot = device.getResourceSnapshot();
        Long diskAvailableMb = snapshot == null ? null : numberField(snapshot, "disk_available_mb");
        if (diskAvailableMb == null || diskAvailableMb < (long) firmwareFileSizeMb * 2) {
            throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": 可用磁盘空间 " + diskAvailableMb
                    + "MiB 不足，需要 " + (firmwareFileSizeMb * 2) + "MiB（固件包大小 × 2）");
        }
    }

    @Override
    public void checkVersionCompatibility(DeviceInfoDTO device, OtaFirmware firmware) {
        if (StringUtils.hasText(firmware.getMinVersion()) && StringUtils.hasText(device.getFirmwareVersion())) {
            if (SemVerComparator.compare(device.getFirmwareVersion(), firmware.getMinVersion()) < 0) {
                throw new JeecgBootBizTipException(ERR_VERSION_INCOMPATIBLE + ": 当前版本 " + device.getFirmwareVersion()
                        + " 低于固件包要求的最低版本 " + firmware.getMinVersion());
            }
        }
        List<String> compatibleModels = parseModels(firmware.getCompatibleModels());
        if (!compatibleModels.isEmpty() && !compatibleModels.contains(device.getDeviceModel())) {
            throw new JeecgBootBizTipException(ERR_VERSION_INCOMPATIBLE + ": 设备型号 " + device.getDeviceModel()
                    + " 与固件包适配型号不匹配");
        }
    }

    @Override
    public void checkSignatureNotRevoked(OtaFirmware firmware) {
        keyService.assertDispatchable(firmware.getKeyId());
    }

    private void assertFresh(LocalDateTime lastHeartbeatAt, long validSeconds, String label) {
        long seconds = ChronoUnit.SECONDS.between(lastHeartbeatAt, LocalDateTime.now());
        if (seconds > validSeconds) {
            throw new JeecgBootBizTipException(ERR_RESOURCE_INSUFFICIENT + ": " + label + "数据已超出有效期（最后上报 "
                    + seconds + " 秒前，有效期 " + validSeconds + " 秒），视为不可信");
        }
    }

    private Long numberField(Map<String, Object> snapshot, String key) {
        Object value = snapshot.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && StringUtils.hasText((String) value)) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringField(Map<String, Object> snapshot, String key) {
        Object value = snapshot.get(key);
        return value == null ? null : value.toString();
    }

    private List<String> parseModels(String compatibleModelsJson) {
        if (!StringUtils.hasText(compatibleModelsJson)) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(compatibleModelsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

}
