package org.jeecg.modules.device.geo;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * MQTT 鉴权后写设备 {@code address} 时使用的 IP 行政区划解析，可在高德在线与纯真离线之间切换（无交叉兜底，严格按 provider）。
 */
@Component
@RequiredArgsConstructor
@RefreshScope
public class DeviceIpGeoResolver {

    private final AmapIpGeoClient amapIpGeoClient;
    private final QqWryIpGeoClient qqwryIpGeoClient;
    private final IpGeoCache ipGeoCache;

    @Value("${device.mqtt-auth.ip-geo.provider:amap}")
    private String provider;

    /**
     * @param normalizedIp {@link org.jeecg.modules.device.service.DeviceMqttConnectionAddressService} 规范化后的 IP
     */
    public String resolveAdministrativeAddress(String normalizedIp) {
        if (normalizedIp == null || normalizedIp.isBlank()) {
            return null;
        }
        String providerKey = provider == null ? "amap" : provider.trim();
        String cached = ipGeoCache.get(providerKey, normalizedIp);
        if (cached != null) {
            return cached;
        }

        String resolved = resolveFromProvider(normalizedIp);
        if (resolved != null && !resolved.isBlank()) {
            ipGeoCache.put(providerKey, normalizedIp, resolved);
        }
        return resolved;
    }

    private String resolveFromProvider(String normalizedIp) {
        if (provider != null && "qqwry".equalsIgnoreCase(provider.trim())) {
            return qqwryIpGeoClient.resolveAdministrativeAddress(normalizedIp);
        }
        return amapIpGeoClient.resolveAdministrativeAddress(normalizedIp);
    }
}
