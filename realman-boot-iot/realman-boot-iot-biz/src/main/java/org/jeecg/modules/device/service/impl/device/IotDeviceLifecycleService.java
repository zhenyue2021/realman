package org.jeecg.modules.device.service.impl.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.util.DeviceExcelExportUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 设备生命周期：注册、查询、导出、启停、租户授权。
 * 事务由调用方 {@link org.jeecg.modules.device.service.impl.IotDeviceServiceImpl} 统一控制。
 */
@Service
@RequiredArgsConstructor
public class IotDeviceLifecycleService {

    private final IotDeviceMapper deviceMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final DeviceSecretService secretService;
    private final CommandEncryptService encryptService;
    private final IDeviceOperationLogService logService;
    private final IotDeviceSupport deviceSupport;

    public IotDevice addDevice(IotDevice device) {
        long cnt = deviceMapper.selectCount(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, device.getDeviceCode()));
        if (cnt > 0) {
            throw new RuntimeException("设备编号已存在: " + device.getDeviceCode());
        }
        device.setStatus(DeviceConstant.DeviceStatus.INACTIVE);
        device.setCreateTime(LocalDateTime.now());
        String generateSecret = secretService.generateSecret(device.getDeviceCode());
        device.setDeviceSecret(generateSecret);
        deviceMapper.insert(device);
        logService.recordLog(device.getId(), device.getDeviceCode(),
                DeviceConstant.OperationType.DEVICE_REGISTER,
                "新设备注册: " + device.getDeviceCode(), null,
                DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, null, null);
        return device;
    }

    public IPage<IotDevice> queryDevicePage(Page<IotDevice> page, DeviceRequestDTO request) {
        return deviceMapper.selectDeviceList(
                page,
                request.getDeviceName(),
                request.getDeviceType(),
                request.getStatus(),
                request.getProductId(),
                request.getAuthEffectiveTime(),
                request.getAuthExpireTime(),
                request.getCurrentUsername(),
                request.getCurrentTenantId(),
                request.getSuperAdmin()
        );
    }

    public void updateDevice(String deviceId, DeviceUpdateDTO dto) {
        IotDevice device = deviceSupport.require(deviceId);
        if (dto.getDeviceName() != null) {
            device.setDeviceName(dto.getDeviceName());
        }
        if (dto.getMacAddress() != null) {
            device.setMacAddress(dto.getMacAddress());
        }
        if (dto.getDeviceModel() != null) {
            device.setDeviceModel(dto.getDeviceModel());
        }
        if (dto.getSerialNumber() != null) {
            device.setSerialNumber(dto.getSerialNumber());
        }
        if (dto.getDescription() != null) {
            device.setDescription(dto.getDescription());
        }
        if (dto.getLongitude() != null) {
            device.setLongitude(dto.getLongitude());
        }
        if (dto.getLatitude() != null) {
            device.setLatitude(dto.getLatitude());
        }
        deviceMapper.updateById(device);
    }

    public byte[] exportDeviceList(DeviceRequestDTO requestDTO) {
        int max = DeviceExcelExportUtil.getMaxExportRows();
        IPage<IotDevice> page = deviceMapper.selectDeviceList(
                new Page<>(1, max),
                requestDTO.getDeviceName(),
                requestDTO.getDeviceType(),
                requestDTO.getStatus(),
                requestDTO.getProductId(),
                requestDTO.getAuthEffectiveTime(),
                requestDTO.getAuthExpireTime(),
                requestDTO.getCurrentUsername(),
                requestDTO.getCurrentTenantId(),
                requestDTO.getSuperAdmin()
        );
        try {
            return DeviceExcelExportUtil.exportDevices(page.getRecords());
        } catch (Exception e) {
            throw new RuntimeException("导出Excel失败", e);
        }
    }

    public void changeDeviceStatus(String deviceId, Integer status, String operator) {
        IotDevice device = deviceSupport.require(deviceId);
        device.setStatus(status);
        deviceMapper.updateById(device);
        if (DeviceConstant.DeviceStatus.DISABLED == status) {
            secretService.evict(device.getDeviceCode());
            encryptService.evictCache(device.getDeviceCode());
        }
    }

    public Map<String, IotDeviceAuth> loadTenantAuth(List<String> deviceIds, String tenantId, String deviceType) {
        if (deviceIds == null || deviceIds.isEmpty() || tenantId == null || tenantId.isBlank()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<IotDeviceAuth> w = new LambdaQueryWrapper<IotDeviceAuth>()
                .eq(IotDeviceAuth::getTenantId, tenantId)
                .eq(IotDeviceAuth::getStatus, 1)
                .eq(IotDeviceAuth::getDelFlag, 0)
                .and(x -> x.isNull(IotDeviceAuth::getEffectiveTime).or().le(IotDeviceAuth::getEffectiveTime, now))
                .and(x -> x.isNull(IotDeviceAuth::getExpireTime).or().ge(IotDeviceAuth::getExpireTime, now))
                .orderByDesc(IotDeviceAuth::getEffectiveTime);
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.CONTROLLER)) {
            w.in(IotDeviceAuth::getControllerId, deviceIds);
        }
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.ROBOT)) {
            w.in(IotDeviceAuth::getDeviceId, deviceIds);
        }
        List<IotDeviceAuth> auths = deviceAuthMapper.selectList(w);
        if (auths == null || auths.isEmpty()) {
            return Map.of();
        }
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.CONTROLLER)) {
            return auths.stream()
                    .filter(a -> a.getControllerId() != null)
                    .collect(Collectors.toMap(IotDeviceAuth::getControllerId, a -> a, (a, b) -> a));
        }
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.ROBOT)) {
            return auths.stream()
                    .filter(a -> a.getDeviceId() != null)
                    .collect(Collectors.toMap(IotDeviceAuth::getDeviceId, a -> a, (a, b) -> a));
        }
        return Map.of();
    }
}
