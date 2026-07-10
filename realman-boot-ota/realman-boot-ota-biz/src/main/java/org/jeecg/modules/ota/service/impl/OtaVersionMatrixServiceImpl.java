package org.jeecg.modules.ota.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceBatchQueryRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.mapper.OtaFirmwareMapper;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.service.IOtaVersionMatrixService;
import org.jeecg.modules.ota.util.SemVerComparator;
import org.jeecg.modules.ota.vo.DeviceVersionRow;
import org.jeecg.modules.ota.vo.VersionMatrixQuery;
import org.jeecg.modules.ota.vo.VersionMatrixResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.VERSION_LAG_CRITICAL_MINOR_DIFF;
import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.VERSION_LAG_WARN_MINOR_DIFF;

/**
 * 版本矩阵实现，对齐 OTA 平台详细设计十二章（PRD 4.3、9.4.4）。
 *
 * <p>版本落后 warn/critical 阈值可经 {@code ota_system_setting} 配置
 * （{@code version_lag_warn_minor_diff}/{@code version_lag_critical_minor_diff}，
 * 默认 2/5，对齐 PRD 默认值）；大版本号落后 &gt;=1 恒判定为 critical，
 * 视为结构性规则不纳入配置（PRD 9.9 的 17 项交叉校验清单未包含此项，仅正文提及
 * "可在系统设置中配置"，此前本轮曾硬编码，现已按此补齐）。
 */
@Service
@RequiredArgsConstructor
public class OtaVersionMatrixServiceImpl implements IOtaVersionMatrixService {

    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final OtaFirmwareMapper firmwareMapper;
    private final IOtaSystemSettingService systemSettingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public VersionMatrixResult getMatrix(VersionMatrixQuery query) {
        DeviceBatchQueryRequest request = new DeviceBatchQueryRequest();
        request.setDeviceType(DeviceType.valueOf(query.getDeviceType().toUpperCase()));
        request.setDeviceModel(query.getModel());
        Result<List<DeviceInfoDTO>> result = deviceInfoFeignClient.batchQuery(request);
        List<DeviceInfoDTO> devices = result != null && result.isSuccess() && result.getResult() != null
                ? result.getResult() : List.of();

        String clusterMax = devices.stream()
                .map(DeviceInfoDTO::getFirmwareVersion)
                .filter(SemVerComparator::isValid)
                .max(SemVerComparator::compare)
                .orElse(null);
        String repoLatest = findLatestRepoVersion(query.getDeviceType(), query.getModel());

        List<DeviceVersionRow> rows = devices.stream().map(device -> toRow(device, clusterMax, repoLatest))
                .collect(Collectors.toList());

        Map<String, Long> distribution = new TreeMap<>();
        for (DeviceInfoDTO device : devices) {
            if (SemVerComparator.isValid(device.getFirmwareVersion())) {
                distribution.merge(device.getFirmwareVersion(), 1L, Long::sum);
            }
        }

        VersionMatrixResult matrixResult = new VersionMatrixResult();
        matrixResult.setVersions(distribution.keySet().stream()
                .sorted(SemVerComparator::compare).collect(Collectors.toList()));
        matrixResult.setVersionDistribution(distribution);
        matrixResult.setLatestRepoVersion(repoLatest);
        matrixResult.setDevices(rows);
        return matrixResult;
    }

    private DeviceVersionRow toRow(DeviceInfoDTO device, String clusterMax, String repoLatest) {
        DeviceVersionRow row = new DeviceVersionRow();
        row.setDeviceId(device.getDeviceId());
        row.setDeviceCode(device.getDeviceCode());
        row.setCurrentVersion(device.getFirmwareVersion());
        row.setOnline(device.getOnlineStatus() == OnlineStatus.ONLINE);

        int warnMinorDiff = systemSettingService.getInt(VERSION_LAG_WARN_MINOR_DIFF);
        int criticalMinorDiff = systemSettingService.getInt(VERSION_LAG_CRITICAL_MINOR_DIFF);
        boolean validVersion = SemVerComparator.isValid(device.getFirmwareVersion());
        String clusterLevel = (validVersion && clusterMax != null)
                ? lagLevel(device.getFirmwareVersion(), clusterMax, warnMinorDiff, criticalMinorDiff) : "none";
        String repoLevel = (validVersion && repoLatest != null)
                ? lagLevel(device.getFirmwareVersion(), repoLatest, warnMinorDiff, criticalMinorDiff) : "none";
        row.setVersionLagLevelCluster(clusterLevel);
        row.setVersionLagLevelRepo(repoLevel);

        if (!"none".equals(clusterLevel)) {
            row.setLagReason("低于群内最新版 " + clusterMax);
        } else if (!"none".equals(repoLevel)) {
            // "全员落后"场景：本设备已与群内最新版一致（无群内落后），但仓库有更新版本，见 PRD 4.3
            row.setLagReason("所有设备版本一致，但仓库已有更新版本 " + repoLatest + "，建议升级");
        } else {
            row.setLagReason(null);
        }
        return row;
    }

    /**
     * critical：大版本差异 &gt;=1（结构性规则，不可配置）或小版本差异 &gt; criticalMinorDiff；
     * warn：小版本差异 &gt; warnMinorDiff。阈值来自 {@code ota_system_setting}。
     */
    private String lagLevel(String current, String baseline, int warnMinorDiff, int criticalMinorDiff) {
        if (SemVerComparator.compare(current, baseline) >= 0) {
            return "none";
        }
        String[] currentParts = current.substring(1).split("\\.");
        String[] baselineParts = baseline.substring(1).split("\\.");
        int majorDiff = Integer.parseInt(baselineParts[0]) - Integer.parseInt(currentParts[0]);
        int minorDiff = Integer.parseInt(baselineParts[1]) - Integer.parseInt(currentParts[1]);
        if (majorDiff >= 1 || minorDiff > criticalMinorDiff) {
            return "critical";
        }
        if (minorDiff > warnMinorDiff) {
            return "warn";
        }
        return "none";
    }

    private String findLatestRepoVersion(String deviceType, String model) {
        List<OtaFirmware> firmwares = firmwareMapper.selectList(Wrappers.<OtaFirmware>lambdaQuery()
                .eq(OtaFirmware::getDeviceType, deviceType)
                .eq(OtaFirmware::getRiskLevel, "normal"));
        return firmwares.stream()
                .filter(f -> matchesModel(f.getCompatibleModels(), model))
                .map(OtaFirmware::getVersion)
                .filter(SemVerComparator::isValid)
                .max(SemVerComparator::compare)
                .orElse(null);
    }

    private boolean matchesModel(String compatibleModelsJson, String model) {
        if (!StringUtils.hasText(compatibleModelsJson)) {
            return true;
        }
        try {
            List<String> models = objectMapper.readValue(compatibleModelsJson, new TypeReference<List<String>>() {
            });
            return models.isEmpty() || models.contains(model);
        } catch (Exception e) {
            return true;
        }
    }
}
