package org.jeecg.modules.device.darwin.service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.darwin.config.DarwinProperties;
import org.jeecg.modules.device.util.MinioUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DarwinFileStorageService {

    private final MinioClient minioClient;
    private final MinioUtil minioUtil;
    private final DarwinProperties darwinProperties;

    /**
     * 上传文件到 MinIO，返回预签名下载 URL。
     *
     * @param file    上传文件
     * @param bizType 业务类型（用于 object key 前缀分类）
     * @return 预签名下载 URL
     */
    public String store(MultipartFile file, String bizType) {
        DarwinProperties.FileUpload cfg = darwinProperties.getFileUpload();
        String bucket = cfg.getUploadBucket();
        minioUtil.ensureBucketExists(bucket);

        String ext = extractExtension(file.getOriginalFilename());
        String date = LocalDateTime.now().toString().substring(0, 10); // yyyy-MM-dd
        String objectKey = String.format("darwin/%s/%s/%s%s",
                bizType.toLowerCase(), date, UUID.randomUUID().toString().replace("-", ""), ext);

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("[Darwin] 上传文件到 MinIO 失败: " + e.getMessage(), e);
        }

        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(cfg.getUrlExpireDays(), TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("[Darwin] 生成预签名下载 URL 失败: " + e.getMessage(), e);
        }
    }

    private String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
