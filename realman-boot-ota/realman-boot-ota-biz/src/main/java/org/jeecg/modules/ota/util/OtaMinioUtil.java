package org.jeecg.modules.ota.util;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.ota.config.OtaFirmwareStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OTA 固件 OSS（MinIO）读写封装，镜像 realman-boot-iot 现有
 * {@code device.util.MinioUtil}/{@code IotOtaServiceImpl} 的用法方式。
 * 仅在 {@code ota.firmware.oss.enabled=true} 时装配，与 {@link OtaMinioConfig}
 * 共用同一开关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ota.firmware.oss", name = "enabled", havingValue = "true")
public class OtaMinioUtil {

    private final MinioClient otaMinioClient;
    private final OtaFirmwareStorageProperties storageProperties;

    private String bucket() {
        return storageProperties.getOss().getBucket();
    }

    private void ensureBucketExists() {
        String bucketName = bucket();
        try {
            boolean exists = otaMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                otaMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("[ota] MinIO bucket 不存在，已自动创建: {}", bucketName);
            }
        } catch (Exception e) {
            throw new JeecgBootBizTipException("检查/创建 OSS 存储桶失败: " + e.getMessage());
        }
    }

    public void putObject(String objectName, InputStream stream, long size, String contentType) {
        ensureBucketExists();
        try {
            otaMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket()).object(objectName)
                    .stream(stream, size, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new JeecgBootBizTipException("上传 OSS 失败: " + e.getMessage());
        }
    }

    public String presignedUrl(String objectName, long expirySeconds) {
        try {
            return otaMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket()).object(objectName)
                    .expiry((int) Math.min(expirySeconds, Integer.MAX_VALUE), TimeUnit.SECONDS).build());
        } catch (Exception e) {
            throw new JeecgBootBizTipException("生成 OSS 预签名下载 URL 失败: " + e.getMessage());
        }
    }

    public void removeObject(String objectName) {
        try {
            otaMinioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(objectName).build());
        } catch (Exception e) {
            log.warn("[ota] OSS 对象清理失败，可能孤立 object={}: {}", objectName, e.getMessage());
        }
    }

    /** 列出指定前缀下的对象名（非递归语义由调用方按需过滤），用于 operate=1 扫描。 */
    public List<String> listObjectNames(String prefix) {
        ensureBucketExists();
        List<String> names = new ArrayList<>();
        Iterable<Result<Item>> results = otaMinioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket()).prefix(prefix).recursive(false).build());
        try {
            for (Result<Item> result : results) {
                Item item = result.get();
                if (!item.isDir()) {
                    names.add(item.objectName());
                }
            }
        } catch (Exception e) {
            throw new JeecgBootBizTipException("扫描 OSS 存储桶失败: " + e.getMessage());
        }
        return names;
    }

    public boolean objectExists(String objectName) {
        try {
            otaMinioClient.statObject(io.minio.StatObjectArgs.builder().bucket(bucket()).object(objectName).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
