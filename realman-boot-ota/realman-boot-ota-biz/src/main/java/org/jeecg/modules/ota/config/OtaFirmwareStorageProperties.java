package org.jeecg.modules.ota.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 固件包本地存储配置。对齐 OTA 平台详细设计 3.3（PRD 4.2.5）。
 *
 * <p>已知限制：本轮只实现本地磁盘存储（{@code storage-dir}）与本地盘扫描
 * （{@code scan-paths}），OSS/S3 真实 SDK 集成留待接入具体云厂商凭证时补充，
 * 见 {@code IOtaFirmwareService} 类注释。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ota.firmware")
public class OtaFirmwareStorageProperties {

    /** 固件包与签名文件的本地存储根目录 */
    private String storageDir = "/data/ota/firmware";

    /** 本地盘扫描路径（U 盘等），逗号分隔，对应 PRD 4.2.5 operate=0 */
    private List<String> localScanPaths = List.of("/tmp", "/media/realman");
}
