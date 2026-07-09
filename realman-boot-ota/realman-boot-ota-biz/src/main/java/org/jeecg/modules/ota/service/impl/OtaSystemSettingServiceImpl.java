package org.jeecg.modules.ota.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.ota.config.OtaSystemSettingDefaults;
import org.jeecg.modules.ota.entity.OtaSystemSetting;
import org.jeecg.modules.ota.mapper.OtaSystemSettingMapper;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.service.OtaAuditService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.*;

/**
 * 系统设置读写 + 交叉校验实现，逐字对齐 OTA 平台详细设计第十章（PRD 9.9）的
 * 校验规则表。每次读取都查库（管理端配置读取频率低，不引入额外缓存层，
 * 避免"改了配置但还在用旧值"的缓存一致性问题）。
 */
@Service
@RequiredArgsConstructor
public class OtaSystemSettingServiceImpl implements IOtaSystemSettingService {

    private static final String ERR_CONFIG_CONFLICT = "ERR_CONFIG_CONFLICT";
    private static final Set<String> ENUM_FAIL_THRESHOLD_TYPE = Set.of("count", "percent");
    private static final Set<String> ENUM_ON_THRESHOLD_EXCEEDED = Set.of("pause", "stop_all", "continue");
    private static final Set<String> ENUM_BOOLEAN = Set.of("true", "false");

    private final OtaSystemSettingMapper settingMapper;
    private final OtaAuditService auditService;

    @Override
    public Map<String, String> getAll() {
        Map<String, String> result = new HashMap<>(DEFAULTS);
        List<OtaSystemSetting> rows = settingMapper.selectList(Wrappers.emptyWrapper());
        for (OtaSystemSetting row : rows) {
            result.put(row.getSettingKey(), row.getSettingValue());
        }
        return result;
    }

    @Override
    public String getString(String key) {
        OtaSystemSetting row = settingMapper.selectById(key);
        return row != null ? row.getSettingValue() : DEFAULTS.get(key);
    }

    @Override
    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    @Override
    public long getLong(String key) {
        return Long.parseLong(getString(key));
    }

    @Override
    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    @Override
    public void validate(Map<String, String> changes) {
        Map<String, String> effective = getAll();
        effective.putAll(changes);

        long heartbeat = parseLong(effective, HEARTBEAT_INTERVAL_SECONDS);
        if (heartbeat < 10) {
            throw conflict(HEARTBEAT_INTERVAL_SECONDS, "不可小于 10 秒");
        }
        long minResourceValid = Math.min(
                Math.min(parseLong(effective, DISK_VALID_SECONDS), parseLong(effective, MEMORY_VALID_SECONDS)),
                Math.min(parseLong(effective, POWER_VALID_SECONDS), parseLong(effective, NETWORK_VALID_SECONDS)));
        if (heartbeat >= minResourceValid) {
            throw conflict(HEARTBEAT_INTERVAL_SECONDS,
                    "必须严格小于 disk/memory/power/network_valid_seconds 中的最小值（当前最小值 " + minResourceValid + " 秒）");
        }

        long deviceOfflineHours = parseLong(effective, DEVICE_OFFLINE_TIMEOUT_HOURS);
        if (deviceOfflineHours < 1) {
            throw conflict(DEVICE_OFFLINE_TIMEOUT_HOURS, "不可小于 1 小时");
        }
        long pendingOnlineMinutes = parseLong(effective, PENDING_ONLINE_DEVICE_TIMEOUT_MINUTES);
        if (pendingOnlineMinutes < 1 || pendingOnlineMinutes > deviceOfflineHours * 60) {
            throw conflict(PENDING_ONLINE_DEVICE_TIMEOUT_MINUTES,
                    "须在 1 分钟 ~ device_offline_timeout_hours×60（" + (deviceOfflineHours * 60) + " 分钟）之间");
        }

        long cancelAckTimeout = parseLong(effective, CANCEL_ACK_TIMEOUT_SECONDS);
        if (cancelAckTimeout < 10 || cancelAckTimeout > 300) {
            throw conflict(CANCEL_ACK_TIMEOUT_SECONDS, "须在 10~300 秒之间");
        }

        long tokenExpiryDays = parseLong(effective, DEVICE_TOKEN_EXPIRY_DAYS);
        if (tokenExpiryDays < 30 || tokenExpiryDays > 3650) {
            throw conflict(DEVICE_TOKEN_EXPIRY_DAYS, "须在 30~3650 天之间");
        }

        long secretExpiryDays = parseLong(effective, REGISTRATION_SECRET_EXPIRY_DAYS);
        if (secretExpiryDays < 1 || secretExpiryDays > 3650) {
            throw conflict(REGISTRATION_SECRET_EXPIRY_DAYS, "须在 1~3650 天之间");
        }

        String failThresholdType = effective.get(DEFAULT_FAIL_THRESHOLD_TYPE);
        if (!ENUM_FAIL_THRESHOLD_TYPE.contains(failThresholdType)) {
            throw conflict(DEFAULT_FAIL_THRESHOLD_TYPE, "须为 count 或 percent");
        }
        long failThreshold = parseLong(effective, DEFAULT_FAIL_THRESHOLD);
        if (failThreshold < 0) {
            throw conflict(DEFAULT_FAIL_THRESHOLD, "不可为负数");
        }
        String onThresholdExceeded = effective.get(DEFAULT_ON_THRESHOLD_EXCEEDED);
        if (!ENUM_ON_THRESHOLD_EXCEEDED.contains(onThresholdExceeded)) {
            throw conflict(DEFAULT_ON_THRESHOLD_EXCEEDED, "须为 pause / stop_all / continue");
        }

        long maxFirmwareSizeMb = parseLong(effective, MAX_FIRMWARE_SIZE_MB);
        if (maxFirmwareSizeMb <= 0) {
            throw conflict(MAX_FIRMWARE_SIZE_MB, "必须为正数");
        }
        long maxBatchDevices = parseLong(effective, MAX_BATCH_DEVICES);
        if (maxBatchDevices <= 0) {
            throw conflict(MAX_BATCH_DEVICES, "必须为正数");
        }
        String globalSigVerifyEnabled = effective.get(GLOBAL_SIG_VERIFY_ENABLED);
        if (!ENUM_BOOLEAN.contains(globalSigVerifyEnabled)) {
            throw conflict(GLOBAL_SIG_VERIFY_ENABLED, "须为 true 或 false");
        }
    }

    @Override
    public void validateAndApply(Map<String, String> changes, String operator) {
        validate(changes);
        LocalDateTime now = LocalDateTime.now();
        changes.forEach((key, value) -> {
            OtaSystemSetting existing = settingMapper.selectById(key);
            OtaSystemSetting row = existing != null ? existing : new OtaSystemSetting();
            row.setSettingKey(key);
            row.setSettingValue(value);
            row.setUpdatedBy(operator);
            row.setUpdatedAt(now);
            if (existing == null) {
                settingMapper.insert(row);
            } else {
                settingMapper.updateById(row);
            }
        });
        auditService.write("SYSTEM_SETTING_UPDATE", operator, "normal", changes);
    }

    private long parseLong(Map<String, String> effective, String key) {
        try {
            return Long.parseLong(effective.get(key));
        } catch (Exception e) {
            throw conflict(key, "取值非法：" + effective.get(key));
        }
    }

    private JeecgBootBizTipException conflict(String key, String reason) {
        return new JeecgBootBizTipException(ERR_CONFIG_CONFLICT + ": " + key + " " + reason);
    }
}
