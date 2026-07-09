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
import org.jeecg.modules.ota.service.IOtaVersionMatrixService;
import org.jeecg.modules.ota.util.SemVerComparator;
import org.jeecg.modules.ota.vo.DeviceVersionRow;
import org.jeecg.modules.ota.vo.VersionMatrixQuery;
import org.jeecg.modules.ota.vo.VersionMatrixResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 版本矩阵实现，对齐 OTA 平台详细设计十二章（PRD 4.3、9.4.4）。
 *
 * <p>已知限制：版本落后 warn/critical 阈值本轮按 PRD 默认值硬编码（小版本差异 &gt;2
 * 为 warn，大版本差异 &gt;=1 或小版本差异 &gt;5 为 critical），未纳入
 * {@code ota_system_setting}（PRD 9.9 的 17 项交叉校验清单未包含此项，仅正文提及
 * "可在系统设置中配置"），如需可配置化可后续追加设置项。
 */
@Service
@RequiredArgsConstructor
public class OtaVersionMatrixServiceImpl implements IOtaVersionMatrixService {

    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final OtaFirmwareMapper firmwareMapper;
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

        boolean validVersion = SemVerComparator.isValid(device.getFirmwareVersion());
        String clusterLevel = (validVersion && clusterMax != null) ? lagLevel(device.getFirmwareVersion(), clusterMax) : "none";
        String repoLevel = (validVersion && repoLatest != null) ? lagLevel(device.getFirmwareVersion(), repoLatest) : "none";
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

    /** warn：小版本差异 &gt;2（大版本相同）；critical：大版本差异 &gt;=1 或小版本差异 &gt;5。 */
    private String lagLevel(String current, String baseline) {
        String[] currentParts = current.substring(1).split("\\.");
        String[] baselineParts = baseline.substring(1).split("\\.");
        int currentMajor = Integer.parseInt(currentParts[0]);
        int baselineMajor = Integer.parseInt(baselineParts[0]);
        int currentMinor = Integer.parseInt(currentParts[1]);
        int baselineMinor = Integer.parseInt(baselineParts[1]);
        if (SemVerComparator.compare(current, baseline) >= 0) {
            return "none";
        }
        int majorDiff = baselineMajor - currentMajor;
        int minorDiff = baselineMinor - currentMinor;
        if (majorDiff >= 1 || (majorDiff == 0 && minorDiff > 5)) {
            return "critical";
        }
        if (majorDiff == 0 && minorDiff > 2) {
            return "warn";
        }
        return majorDiff > 0 ? "warn" : "none";
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
