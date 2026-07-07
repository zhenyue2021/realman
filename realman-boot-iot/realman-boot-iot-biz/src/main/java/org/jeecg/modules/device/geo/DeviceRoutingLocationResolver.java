package org.jeecg.modules.device.geo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 为 turn_router 调度解析设备省/市：优先使用 {@code iot_device.address} 文案；
 * 若库中仍为纯 IP（历史数据或 MQTT 异步更新未完成），则经 {@link DeviceIpGeoResolver} 解析（可走 Redis 缓存）。
 */
@Component
@RequiredArgsConstructor
public class DeviceRoutingLocationResolver {

    private static final String PRIVATE_IP_PREFIX = "内网IP-";

    private final DeviceIpGeoResolver deviceIpGeoResolver;

    public AdministrativeAddressParser.ProvinceCity resolve(String address) {
        return AdministrativeAddressParser.parse(normalizeAdministrativeText(address));
    }

    private String normalizeAdministrativeText(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("设备地理位置未上报");
        }
        String text = address.trim();
        String ip = extractIpIfStoredAsIp(text);
        if (ip == null) {
            return text;
        }
        String resolved = deviceIpGeoResolver.resolveAdministrativeAddress(ip);
        if (resolved != null && !resolved.isBlank() && extractIpIfStoredAsIp(resolved) == null) {
            return resolved;
        }
        return text;
    }

    /**
     * @return 若 address 字段存的是 IP（或内网 IP 文案），返回可解析的 IP；否则 null
     */
    static String extractIpIfStoredAsIp(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith(PRIVATE_IP_PREFIX)) {
            String ip = s.substring(PRIVATE_IP_PREFIX.length()).trim();
            return ip.isEmpty() ? null : ip;
        }
        return looksLikeIpv4(s) ? s : null;
    }

    static boolean looksLikeIpv4(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String[] oct = host.split("\\.", -1);
        if (oct.length != 4) {
            return false;
        }
        for (String p : oct) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            int n = Integer.parseInt(p);
            if (n < 0 || n > 255) {
                return false;
            }
        }
        return true;
    }
}
