package org.jeecg.modules.device.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.geo.DeviceIpGeoResolver;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * MQTT 连接鉴权成功后异步更新设备侧信息（不阻塞 EMQX HTTP Auth）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceMqttConnectionAddressService {

    private final IotDeviceMapper deviceMapper;
    private final DeviceIpGeoResolver deviceIpGeoResolver;

    /**
     * 将 EMQX {@code peerhost} 规范为 IP，再解析为行政区划文案（{@code device.mqtt-auth.ip-geo.provider}=amap|qqwry），写入 {@code iot_device.address}；
     * 解析失败或未配置数据源时写入纯 IP；内网 IP 写入「内网IP-{ip}」。
     */
    @Async("devicePersistExecutor")
    public void updateAddressAfterAuthSuccess(String deviceId, String rawPeerHost) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        String normalized = normalizePeerHostToIp(rawPeerHost);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        String addressText = deviceIpGeoResolver.resolveAdministrativeAddress(normalized);
        if (addressText == null || addressText.isBlank()) {
            addressText = normalized;
        }
        try {
            IotDevice patch = new IotDevice();
            patch.setId(deviceId);
            patch.setAddress(addressText);
            deviceMapper.updateById(patch);
            log.debug("[MqttAuth] 已异步更新设备 address deviceId={} address={}", deviceId, addressText);
        } catch (Exception e) {
            log.warn("[MqttAuth] 异步更新设备 address 失败 deviceId={}", deviceId, e);
        }
    }

    static String normalizePeerHostToIp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // IPv6: [::1] 或 [fe80::1]:1883
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 1) {
                return s.substring(1, end);
            }
            return s;
        }
        // IPv4:port → 去端口（仅当恰好一个冒号且左侧像 IPv4）
        int lastColon = s.lastIndexOf(':');
        if (lastColon > 0 && s.indexOf(':') == lastColon) {
            String hostPart = s.substring(0, lastColon);
            if (looksLikeIpv4(hostPart)) {
                return hostPart;
            }
        }
        return s;
    }

    private static boolean looksLikeIpv4(String host) {
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
