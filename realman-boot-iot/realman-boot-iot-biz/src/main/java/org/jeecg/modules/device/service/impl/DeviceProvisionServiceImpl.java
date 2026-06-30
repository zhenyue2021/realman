package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.Md5Util;
import org.jeecg.modules.device.config.DeviceProvisionProperties;
import org.jeecg.modules.device.constant.DeviceConstant;
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
            throw new IllegalStateException("设备自注册功能未启用");
        }

        String deviceCode = normalizeDeviceCode(request.getDeviceCode());
        String macAddress = requireMacAddress(request.getMacAddress());
        int deviceType = parseDeviceType(request.getDeviceType());
        String deviceModel = request.getDeviceModel().trim();

        validateTimestamp(request.getTimestamp());
        validateSignature(deviceCode, macAddress, request.getTimestamp(), request.getSign());

        assertMacNotBoundToOtherDevice(macAddress, deviceCode);

        IotDevice existing = findExistingDevice(deviceCode, macAddress);
        if (existing != null) {
            if (Objects.equals(existing.getStatus(), DeviceConstant.DeviceStatus.DISABLED)) {
                throw new IllegalStateException("设备已禁用，无法注册: " + existing.getDeviceCode());
            }
            log.info("[Provision] 设备已存在，幂等返回 deviceCode={}", existing.getDeviceCode());
            return toResponse(existing, true);
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
        return toResponse(saved, true);
    }

    private void validateTimestamp(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            throw new IllegalArgumentException("timestamp 无效");
        }
        long skewMs = Duration.ofSeconds(provisionProperties.getTimestampSkewSeconds()).toMillis();
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - timestamp) > skewMs) {
            throw new IllegalArgumentException("请求已过期，请校准设备时间后重试");
        }
    }

    static void validateSignature(String deviceCode, String macAddress, long timestamp, String sign) {
        if (sign == null || sign.isBlank()) {
            throw new IllegalArgumentException("sign 不能为空");
        }
        String payload = deviceCode + "|" + macAddress + "|" + timestamp;
        String expected = Md5Util.md5Encode(payload, "UTF_8").toUpperCase(Locale.ROOT);
        if (!expected.equals(sign.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("签名校验失败");
        }
    }

    private static int parseDeviceType(String deviceType) {
        if (deviceType == null || deviceType.isBlank()) {
            throw new IllegalArgumentException("deviceType 不能为空");
        }
        String normalized = deviceType.trim();
        if (!ALLOWED_DEVICE_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("deviceType 不合法，仅支持 1(机器人) 或 2(主控)");
        }
        return Integer.parseInt(normalized);
    }

    private void assertMacNotBoundToOtherDevice(String macAddress, String deviceCode) {
        IotDevice byMac = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getMacAddress, macAddress)
                .last("LIMIT 1"));
        if (byMac != null && !deviceCode.equals(byMac.getDeviceCode())) {
            throw new IllegalStateException("MAC 地址已被其他设备占用: " + macAddress);
        }
    }

    private IotDevice findExistingDevice(String deviceCode, String macAddress) {
        IotDevice byCode = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .last("LIMIT 1"));
        if (byCode != null) {
            return byCode;
        }
        return deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getMacAddress, macAddress)
                .last("LIMIT 1"));
    }

    private static String normalizeDeviceCode(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode 不能为空");
        }
        return deviceCode.trim();
    }

    private static String requireMacAddress(String macAddress) {
        if (macAddress == null || macAddress.isBlank()) {
            throw new IllegalArgumentException("macAddress 不能为空");
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

    private DeviceProvisionResponseDTO toResponse(IotDevice device, boolean newlyRegistered) {
        return DeviceProvisionResponseDTO.builder()
                .deviceCode(device.getDeviceCode())
                .mqttPassword(device.getDeviceSecret())
                .mqttBrokerUrl(mqttBrokerUrl)
                .newlyRegistered(newlyRegistered)
                .status(device.getStatus())
                .build();
    }
}
