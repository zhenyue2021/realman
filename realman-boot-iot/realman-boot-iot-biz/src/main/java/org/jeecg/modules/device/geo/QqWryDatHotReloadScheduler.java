package org.jeecg.modules.device.geo;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 与运维定时替换 {@code qqwry.dat} 配合：按间隔比对文件 mtime，必要时热加载内存库。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "device.mqtt-auth.ip-geo.qqwry.hot-reload", havingValue = "true")
public class QqWryDatHotReloadScheduler {

    private final QqWryIpGeoClient qqWryIpGeoClient;

    @Scheduled(fixedDelayString = "${device.mqtt-auth.ip-geo.qqwry.reload-check-ms:3600000}")
    public void checkAndReload() {
        qqWryIpGeoClient.tryReloadFromDiskIfModified();
    }
}
