package org.jeecg.modules.device.datacollect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 deviceCode 解析 tenantId，带 JVM 本地缓存，减少 datacollect 路径上的 DB 查询。
 */
@Component
@RequiredArgsConstructor
public class DeviceTenantResolver {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final IotDeviceMapper deviceMapper;
    private final ConcurrentHashMap<String, TenantCacheEntry> tenantByDevice = new ConcurrentHashMap<>();

    public String resolveTenantId(String deviceCode) {
        long now = System.currentTimeMillis();
        TenantCacheEntry cached = tenantByDevice.get(deviceCode);
        if (cached != null && now - cached.cachedAtMs < CACHE_TTL_MS) {
            return cached.tenantId;
        }
        IotDevice device = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, deviceCode));
        String tenant = device != null && device.getTenantId() != null
                ? String.valueOf(device.getTenantId()) : "";
        tenantByDevice.put(deviceCode, new TenantCacheEntry(tenant, now));
        return tenant;
    }

    private record TenantCacheEntry(String tenantId, long cachedAtMs) {
    }
}
