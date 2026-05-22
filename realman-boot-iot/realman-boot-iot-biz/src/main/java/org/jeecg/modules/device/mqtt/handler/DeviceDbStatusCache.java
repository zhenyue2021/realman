package org.jeecg.modules.device.mqtt.handler;

import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备 DB 状态 JVM 缓存：keepalive 路径快速判断「DB 是否已 ONLINE」，避免重复异步写库。
 * 离线事件（$SYS / 定时任务）需同步 {@link #setStatus} 失效缓存。
 */
@Component
public class DeviceDbStatusCache {

    private final ConcurrentHashMap<String, Integer> statusByDeviceCode = new ConcurrentHashMap<>();

    public boolean isOnline(String deviceCode) {
        return Objects.equals(statusByDeviceCode.get(deviceCode), DeviceConstant.DeviceStatus.ONLINE);
    }

    public void setStatus(String deviceCode, int status) {
        statusByDeviceCode.put(deviceCode, status);
    }

    public void clear(String deviceCode) {
        statusByDeviceCode.remove(deviceCode);
    }
}
