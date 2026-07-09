package org.jeecg.modules.ota.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.config.OtaFirmwareStorageProperties;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaKey;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.enums.NonTerminalStates;
import org.jeecg.modules.ota.mapper.OtaFirmwareMapper;
import org.jeecg.modules.ota.mapper.OtaKeyMapper;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaTaskMapper;
import org.jeecg.modules.ota.service.IOtaFirmwareService;
import org.jeecg.modules.ota.service.IOtaKeyService;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.service.OtaAuditService;
import org.jeecg.modules.ota.vo.FirmwareDTO;
import org.jeecg.modules.ota.vo.FirmwareListQuery;
import org.jeecg.modules.ota.vo.FirmwareUploadMetadata;
import org.jeecg.modules.ota.vo.LocalScanResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.MAX_FIRMWARE_SIZE_MB;

/**
 * 固件包管理实现，对齐 OTA 平台详细设计 3.2/3.3（PRD 4.2.1/4.2.5）。
 * 已知限制见 {@link IOtaFirmwareService} 类注释（本地存储可用，OSS 未接入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtaFirmwareServiceImpl implements IOtaFirmwareService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("[vV](\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int SIG_RAW_LENGTH = 64;
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    private final OtaFirmwareMapper firmwareMapper;
    private final OtaKeyMapper keyMapper;
    private final OtaTaskMapper taskMapper;
    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final IOtaKeyService keyService;
    private final IOtaSystemSettingService systemSettingService;
    private final OtaAuditService auditService;
    private final OtaFirmwareStorageProperties storageProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public FirmwareDTO upload(MultipartFile firmwareFile, MultipartFile sigFile, FirmwareUploadMetadata metadata, String operator) {
        if (firmwareFile == null || firmwareFile.isEmpty()) {
            throw new JeecgBootBizTipException("固件包文件不能为空");
        }
        if (sigFile == null || sigFile.isEmpty()) {
            throw new JeecgBootBizTipException("ERR_SIG_FILE_MISSING");
        }

        String originalName = firmwareFile.getOriginalFilename();
        String deviceTypeFromName = detectDeviceType(originalName);
        if (!deviceTypeFromName.equalsIgnoreCase(metadata.getDeviceType())) {
            log.warn("[ota] 固件包文件名角色字段与所选设备类型不一致 fileName={} selected={}", originalName, metadata.getDeviceType());
        }
        String version = normalizeVersion(originalName);

        long fileSizeMb = Math.max(1, firmwareFile.getSize() / BYTES_PER_MIB);
        long maxFirmwareSizeMb = systemSettingService.getLong(MAX_FIRMWARE_SIZE_MB);
        if (fileSizeMb > maxFirmwareSizeMb) {
            throw new JeecgBootBizTipException("ERR_FIRMWARE_TOO_LARGE: " + fileSizeMb + "MiB 超过上限 " + maxFirmwareSizeMb + "MiB");
        }

        byte[] sigRaw = decodeAndValidateSig(sigFile);

        OtaKey activeKey = keyMapper.selectOne(Wrappers.<OtaKey>lambdaQuery().eq(OtaKey::getStatus, "active"));
        if (activeKey == null) {
            throw new JeecgBootBizTipException("ERR_KEY_REVOKED: 当前无 active 状态的 OTA 公钥，无法上传固件包");
        }

        String sha256 = computeSha256(firmwareFile);
        Path targetDir = Path.of(storageProperties.getStorageDir(), metadata.getDeviceType(), version);
        Path firmwarePath = writeToStorage(firmwareFile, targetDir, originalName);
        Path sigPath = writeSigToStorage(sigRaw, targetDir, originalName);

        OtaFirmware firmware = new OtaFirmware();
        firmware.setPackageId(IdUtil.fastSimpleUUID());
        firmware.setFirmwareFileName(originalName);
        firmware.setDeviceType(metadata.getDeviceType());
        firmware.setVersion(version);
        firmware.setMinVersion(StringUtils.hasText(metadata.getMinVersion()) ? normalizeVersionLiteral(metadata.getMinVersion()) : null);
        firmware.setCompatibleModels(toJsonArray(metadata.getCompatibleModels()));
        firmware.setInstallCommand(metadata.getInstallCommand());
        firmware.setRollbackCommand(metadata.getRollbackCommand());
        firmware.setHealthcheckCommand(metadata.getHealthcheckCommand());
        firmware.setRiskLevel(StringUtils.hasText(metadata.getRiskLevel()) ? metadata.getRiskLevel() : "normal");
        firmware.setCancelableInExecuting(metadata.isCancelableInExecuting());
        firmware.setSha256(sha256);
        firmware.setSigLocalPath(sigPath.toString());
        firmware.setKeyId(activeKey.getKeyId());
        firmware.setStorageSource("LOCAL");
        firmware.setDownloadUrl(firmwarePath.toUri().toString());
        firmware.setFileSizeMb((int) fileSizeMb);
        firmware.setCreatedBy(operator);
        firmwareMapper.insert(firmware);

        auditService.write("UPLOAD_FIRMWARE", operator, null, null, "normal", null, firmware.getPackageId(), activeKey.getKeyId(),
                java.util.Map.of("fileName", originalName, "version", version, "sha256", sha256));
        return toDTO(firmware, activeKey.getStatus());
    }

    @Override
    public List<LocalScanResult> scan(int operate) {
        if (operate == 1) {
            throw new JeecgBootBizTipException("OSS 扫描（operate=1）本轮未实现，需接入真实云厂商 SDK 与凭证后补充");
        }
        List<LocalScanResult> results = new ArrayList<>();
        for (String mountPath : storageProperties.getLocalScanPaths()) {
            File dir = new File(mountPath);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".tar.gz"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                LocalScanResult result = new LocalScanResult();
                result.setFileName(file.getName());
                result.setMountPath(mountPath);
                result.setDeviceType(detectDeviceType(file.getName()));
                result.setVersion(normalizeVersion(file.getName()));
                result.setSigAvailable(new File(mountPath, file.getName() + ".sig").exists());
                results.add(result);
            }
        }
        return results;
    }

    @Override
    public PageResult<FirmwareDTO> list(FirmwareListQuery query) {
        Page<OtaFirmware> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<OtaFirmware> pageResult = firmwareMapper.selectPage(page, Wrappers.<OtaFirmware>lambdaQuery()
                .eq(StringUtils.hasText(query.getDeviceType()), OtaFirmware::getDeviceType, query.getDeviceType())
                .eq(StringUtils.hasText(query.getRiskLevel()), OtaFirmware::getRiskLevel, query.getRiskLevel())
                .orderByDesc(OtaFirmware::getCreatedAt));

        List<FirmwareDTO> records = pageResult.getRecords().stream().map(f -> {
            OtaKey key = keyMapper.selectById(f.getKeyId());
            return toDTO(f, key == null ? "NOT_FOUND" : key.getStatus());
        }).filter(dto -> !StringUtils.hasText(query.getKeyStatus()) || query.getKeyStatus().equalsIgnoreCase(dto.getKeyStatus()))
                .collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    @Override
    public void delete(String packageId, String operator) {
        OtaFirmware firmware = getRequired(packageId);
        List<String> referencingTaskIds = taskMapper.selectList(Wrappers.<org.jeecg.modules.ota.entity.OtaTask>lambdaQuery()
                        .eq(org.jeecg.modules.ota.entity.OtaTask::getPackageId, packageId))
                .stream().map(org.jeecg.modules.ota.entity.OtaTask::getTaskId).collect(Collectors.toList());
        if (!referencingTaskIds.isEmpty()) {
            long nonTerminalCount = taskDeviceMapper.selectList(Wrappers.<OtaTaskDevice>lambdaQuery()
                            .in(OtaTaskDevice::getTaskId, referencingTaskIds))
                    .stream().filter(d -> !NonTerminalStates.isTerminal(d.getState())).count();
            if (nonTerminalCount > 0) {
                throw new JeecgBootBizTipException("固件包 [" + firmware.getFirmwareFileName() + "] 当前被 " + nonTerminalCount
                        + " 个进行中任务引用（含 PAUSED 批量任务），无法删除，请等待任务完成、终止相关任务或对 PAUSED 任务执行 abort 后重试");
            }
        }
        firmwareMapper.deleteById(packageId);
        auditService.write("DELETE_FIRMWARE", operator, null, null, "normal", null, packageId, null,
                java.util.Map.of("fileName", firmware.getFirmwareFileName()));
    }

    @Override
    public OtaFirmware getRequired(String packageId) {
        OtaFirmware firmware = firmwareMapper.selectById(packageId);
        if (firmware == null) {
            throw new JeecgBootBizTipException("固件包不存在：" + packageId);
        }
        return firmware;
    }

    private String detectDeviceType(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("master")) {
            return "master";
        }
        if (lower.contains("slave")) {
            return "slave";
        }
        return "";
    }

    private String normalizeVersion(String fileName) {
        if (fileName == null) {
            throw new JeecgBootBizTipException("ERR_INVALID_VERSION_FORMAT: 文件名缺失版本号");
        }
        Matcher matcher = VERSION_PATTERN.matcher(fileName);
        if (!matcher.find()) {
            throw new JeecgBootBizTipException("ERR_INVALID_VERSION_FORMAT: 文件名未包含合法版本号：" + fileName);
        }
        return "V" + matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    private String normalizeVersionLiteral(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.find()) {
            throw new JeecgBootBizTipException("ERR_INVALID_VERSION_FORMAT: " + version);
        }
        return "V" + matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    private byte[] decodeAndValidateSig(MultipartFile sigFile) {
        String content;
        try {
            content = new String(sigFile.getBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new JeecgBootBizTipException("ERR_SIG_FILE_MISSING: 签名文件读取失败");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            throw new JeecgBootBizTipException("ERR_SIG_FORMAT_INVALID: 签名文件非合法 Base64");
        }
        if (raw.length != SIG_RAW_LENGTH) {
            throw new JeecgBootBizTipException("ERR_SIG_FORMAT_INVALID: 签名解码后长度须为 64 字节，实际 " + raw.length);
        }
        return raw;
    }

    private String computeSha256(MultipartFile file) {
        try {
            return DigestUtil.sha256Hex(file.getInputStream());
        } catch (IOException e) {
            throw new JeecgBootBizTipException("固件包读取失败，无法计算 SHA-256");
        }
    }

    private Path writeToStorage(MultipartFile file, Path targetDir, String fileName) {
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(fileName);
            file.transferTo(target);
            return target;
        } catch (IOException e) {
            throw new JeecgBootBizTipException("固件包写入本地存储失败：" + e.getMessage());
        }
    }

    private Path writeSigToStorage(byte[] sigRaw, Path targetDir, String firmwareFileName) {
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(firmwareFileName + ".sig");
            Files.writeString(target, Base64.getEncoder().encodeToString(sigRaw));
            return target;
        } catch (IOException e) {
            throw new JeecgBootBizTipException("签名文件写入本地存储失败：" + e.getMessage());
        }
    }

    private String toJsonArray(String commaSeparated) {
        if (!StringUtils.hasText(commaSeparated)) {
            return "[]";
        }
        List<String> models = Arrays.stream(commaSeparated.split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(models);
        } catch (Exception e) {
            return "[]";
        }
    }

    private FirmwareDTO toDTO(OtaFirmware firmware, String keyStatus) {
        FirmwareDTO dto = new FirmwareDTO();
        dto.setPackageId(firmware.getPackageId());
        dto.setFirmwareFileName(firmware.getFirmwareFileName());
        dto.setDeviceType(firmware.getDeviceType());
        dto.setVersion(firmware.getVersion());
        dto.setMinVersion(firmware.getMinVersion());
        dto.setCompatibleModels(parseModels(firmware.getCompatibleModels()));
        dto.setInstallCommand(firmware.getInstallCommand());
        dto.setRollbackCommand(firmware.getRollbackCommand());
        dto.setHealthcheckCommand(firmware.getHealthcheckCommand());
        dto.setRiskLevel(firmware.getRiskLevel());
        dto.setCancelableInExecuting(Boolean.TRUE.equals(firmware.getCancelableInExecuting()));
        dto.setSha256(firmware.getSha256());
        dto.setKeyId(firmware.getKeyId());
        dto.setKeyStatus(keyStatus);
        dto.setStorageSource(firmware.getStorageSource());
        dto.setFileSizeMb(firmware.getFileSizeMb());
        dto.setCreatedBy(firmware.getCreatedBy());
        dto.setCreatedAt(firmware.getCreatedAt());
        return dto;
    }

    private List<String> parseModels(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return Arrays.asList(objectMapper.readValue(json, String[].class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
