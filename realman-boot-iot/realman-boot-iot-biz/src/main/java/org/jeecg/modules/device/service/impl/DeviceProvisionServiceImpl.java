package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.Md5Util;
import org.jeecg.modules.device.config.DeviceProvisionProperties;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.DeviceProvisionBizCode;
import org.jeecg.modules.device.dto.DeviceProvisionRequestDTO;
import org.jeecg.modules.device.dto.DeviceProvisionResponseDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IDeviceProvisionService;
import org.jeecg.modules.device.service.impl.device.IotDeviceLifecycleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProvisionServiceImpl implements IDeviceProvisionService {

    private static final Set<String> ALLOWED_DEVICE_TYPES = Set.of(
            DeviceConstant.DeviceType.ROBOT,
            DeviceConstant.DeviceType.CONTROLLER
    );

    private final DeviceProvisionProperties provisionProperties;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceLifecycleService lifecycleService;
    private final IDeviceOperationLogService logService;
    private final DeviceSecretService secretService;

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String mqttBrokerUrl;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceProvisionResponseDTO provision(DeviceProvisionRequestDTO request) {
        if (!provisionProperties.isEnabled()) {
            return bizError(DeviceProvisionBizCode.PROVISION_DISABLED);
        }

        String deviceCode = normalizeDeviceCode(request.getDeviceCode());
        if (deviceCode == null) {
            return bizError(DeviceProvisionBizCode.VALIDATION_ERROR, "deviceCode 不能为空");
        }

        String macAddress = requireMacAddress(request.getMacAddress());
        if (macAddress == null) {
            return bizError(DeviceProvisionBizCode.VALIDATION_ERROR, "macAddress 不能为空");
        }

        Integer deviceType = parseDeviceType(request.getDeviceType());
        if (deviceType == null) {
            if (request.getDeviceType() == null || request.getDeviceType().isBlank()) {
                return bizError(DeviceProvisionBizCode.VALIDATION_ERROR, "deviceType 不能为空");
            }
            return bizError(DeviceProvisionBizCode.DEVICE_TYPE_INVALID);
        }

        if (request.getDeviceModel() == null || request.getDeviceModel().isBlank()) {
            return bizError(DeviceProvisionBizCode.VALIDATION_ERROR, "deviceModel 不能为空");
        }
        String deviceModel = request.getDeviceModel().trim();

        DeviceProvisionResponseDTO timestampError = checkTimestamp(request.getTimestamp());
        if (timestampError != null) {
            return timestampError;
        }

        DeviceProvisionResponseDTO signError = validateSignature(
                deviceCode, macAddress, request.getTimestamp(), request.getSign());
        if (signError != null) {
            return signError;
        }

        IotDevice existing = findExistingDevice(deviceCode);
        if (existing != null) {
            if (Objects.equals(existing.getStatus(), DeviceConstant.DeviceStatus.DISABLED)) {
                return bizError(DeviceProvisionBizCode.DEVICE_DISABLED, existing.getDeviceCode());
            }
            log.info("[Provision] 设备已存在，幂等返回 deviceCode={}", existing.getDeviceCode());
            return toResponse(existing, DeviceProvisionBizCode.ALREADY_REGISTERED, false);
        }

        IotDevice device = new IotDevice();
        device.setDeviceCode(deviceCode);
        device.setDeviceName(resolveDeviceName(request.getDeviceName(), deviceCode, deviceType));
        device.setDeviceType(deviceType);
        device.setDeviceModel(deviceModel);
        device.setMacAddress(macAddress);
        device.setDescription(trimToNull(request.getDescription()));
        device.setTenantId(provisionProperties.getDefaultTenantId());
        device.setStatus(DeviceConstant.DeviceStatus.INACTIVE);
        device.setCreateTime(LocalDateTime.now());
        device.setCreateBy("设备自动注册");
        String generateSecret = secretService.generateSecret(device.getDeviceCode());
        device.setDeviceSecret(generateSecret);
        IotDevice saved = lifecycleService.addDevice(device);
        logService.recordLog(saved.getId(), saved.getDeviceCode(),
                DeviceConstant.OperationType.DEVICE_REGISTER,
                "设备 HTTP 自注册: deviceCode=" + deviceCode + ", deviceType=" + deviceType,
                null, DeviceConstant.OperationSource.DEVICE, "SUCCESS", null, null, null);
        log.info("[Provision] 新设备注册成功 deviceCode={} deviceType={} tenantId={}",
                saved.getDeviceCode(), deviceType, saved.getTenantId());
        return toResponse(saved, DeviceProvisionBizCode.REGISTERED_NEW, true);
    }

    private DeviceProvisionResponseDTO checkTimestamp(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return bizError(DeviceProvisionBizCode.VALIDATION_ERROR, "timestamp 无效");
        }
        long skewMs = Duration.ofSeconds(provisionProperties.getTimestampSkewSeconds()).toMillis();
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - timestamp) > skewMs) {
            return bizError(DeviceProvisionBizCode.TIMESTAMP_EXPIRED);
        }
        return null;
    }

    static DeviceProvisionResponseDTO validateSignature(String deviceCode, String macAddress, long timestamp, String sign) {
        if (sign == null || sign.isBlank()) {
            return bizError(DeviceProvisionBizCode.VALIDATION_ERROR, "sign 不能为空");
        }
        String payload = deviceCode + "|" + macAddress + "|" + timestamp;
        String expected = Md5Util.md5Encode(payload, "UTF_8").toUpperCase(Locale.ROOT);
        if (!expected.equals(sign.trim().toUpperCase(Locale.ROOT))) {
            return bizError(DeviceProvisionBizCode.SIGN_INVALID);
        }
        return null;
    }

    private static Integer parseDeviceType(String deviceType) {
        if (deviceType == null || deviceType.isBlank()) {
            return null;
        }
        String normalized = deviceType.trim();
        if (!ALLOWED_DEVICE_TYPES.contains(normalized)) {
            return null;
        }
        return Integer.parseInt(normalized);
    }

    private IotDevice findExistingDevice(String deviceCode) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .last("LIMIT 1"));
    }

    private static String normalizeDeviceCode(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return null;
        }
        return deviceCode.trim();
    }

    private static String requireMacAddress(String macAddress) {
        if (macAddress == null || macAddress.isBlank()) {
            return null;
        }
        return macAddress.trim();
    }

    private static String resolveDeviceName(String deviceName, String deviceCode, int deviceType) {
        if (deviceName != null && !deviceName.isBlank()) {
            return deviceName.trim();
        }
        return 1 == deviceType ? "机器人-" : "主控设备-" + deviceCode;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private DeviceProvisionResponseDTO toResponse(IotDevice device, DeviceProvisionBizCode bizCode, boolean newlyRegistered) {
        return DeviceProvisionResponseDTO.builder()
                .bizCode(bizCode.getCode())
                .bizMessage(bizCode.formatMessage())
                .deviceCode(device.getDeviceCode())
                .mqttPassword(device.getDeviceSecret())
                .mqttBrokerUrl(mqttBrokerUrl)
                .newlyRegistered(newlyRegistered)
                .status(device.getStatus())
                .build();
    }

    private static DeviceProvisionResponseDTO bizError(DeviceProvisionBizCode bizCode, Object... args) {
        return DeviceProvisionResponseDTO.builder()
                .bizCode(bizCode.getCode())
                .bizMessage(bizCode.formatMessage(args))
                .build();
    }
}
