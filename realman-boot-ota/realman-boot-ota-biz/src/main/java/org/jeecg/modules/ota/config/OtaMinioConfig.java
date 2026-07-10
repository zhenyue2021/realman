package org.jeecg.modules.ota.config;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OTA 固件 OSS 存储的 MinIO 客户端，仅在 {@code ota.firmware.oss.enabled=true}
 * 时装配。与 {@link org.jeecg.modules.ota.util.OtaMinioUtil} 共用同一开关，
 * 保证两者要么同时存在、要么同时不存在（避免 {@code @ConditionalOnBean} 在
 * 组件扫描 bean 与 {@code @Bean} 方法 bean 之间的注册时序不确定性）。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ota.firmware.oss", name = "enabled", havingValue = "true")
public class OtaMinioConfig {

    private final OtaFirmwareStorageProperties storageProperties;

    @Bean
    public MinioClient otaMinioClient() {
        OtaFirmwareStorageProperties.Oss oss = storageProperties.getOss();
        return MinioClient.builder()
                .endpoint(oss.getEndpoint())
                .credentials(oss.getAccessKey(), oss.getSecretKey())
                .build();
    }
}
