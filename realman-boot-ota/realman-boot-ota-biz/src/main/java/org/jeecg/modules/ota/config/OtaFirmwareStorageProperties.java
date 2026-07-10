package org.jeecg.modules.ota.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 固件包存储配置（本地磁盘 + OSS），对齐 OTA 平台详细设计 3.3（PRD 4.2.5）。
 *
 * <p>本地磁盘存储（{@code storage-dir}/{@code scan-paths}）默认启用。OSS 存储
 * 经 {@code oss.enabled=true} 开启，接入 MinIO（S3 兼容协议，凭证走 Spring
 * 配置，与 realman-boot-iot 现有 {@code AppConfig.minioClient()} 用法一致）；
 * 详细设计原文建议凭证经系统设置加密存储，本轮改为沿用代码库既有的
 * 配置文件凭证管理方式，避免另起一套加密存储机制——如后续确需管理端可配置
 * 凭证，应作为独立需求单独实现，而非在此顺带臆造。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ota.firmware")
public class OtaFirmwareStorageProperties {

    /** 固件包与签名文件的本地存储根目录 */
    private String storageDir = "/data/ota/firmware";

    /** 本地盘扫描路径（U 盘等），逗号分隔，对应 PRD 4.2.5 operate=0 */
    private List<String> localScanPaths = List.of("/tmp", "/media/realman");

    private Oss oss = new Oss();

    @Data
    public static class Oss {

        /** 是否启用 OSS（MinIO）存储；默认关闭，不影响现有本地磁盘默认行为 */
        private boolean enabled = false;

        private String endpoint = "http://localhost:9000";

        private String accessKey;

        private String secretKey;

        /** 固件专用存储桶 */
        private String bucket = "ota-firmware";

        /** OSS 扫描（operate=1）的对象前缀，供运维手工投放候选固件文件，对应本地盘扫描的"落盘目录"角色 */
        private String scanPrefix = "inbox/";
    }
}
