package org.jeecg.modules.device.controller.darwin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.darwin.config.DarwinProperties;
import org.jeecg.modules.device.darwin.dto.DarwinOssAuthRequestDTO;
import org.jeecg.modules.device.darwin.service.DarwinFileStorageService;
import org.jeecg.modules.device.darwin.service.DarwinUploadTokenService;
import org.jeecg.modules.device.vo.ApiResult;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/darwin/file")
@RequiredArgsConstructor
@Tag(name = "达尔文文件上传中转")
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class DarwinFileUploadController {

    private final DarwinUploadTokenService tokenService;
    private final DarwinFileStorageService fileStorageService;
    private final DarwinProperties darwinProperties;

    @PostMapping("/upload")
    @Operation(summary = "达尔文文件上传（Token 授权）")
    public ApiResult<Map<String, String>> upload(
            @RequestHeader("X-Upload-Token") String token,
            @RequestPart("file") MultipartFile file) {

        if (file == null || file.isEmpty()) {
            return ApiResult.fail("文件不能为空");
        }

        DarwinOssAuthRequestDTO meta = tokenService.validateAndConsume(token);
        if (meta == null) {
            return ApiResult.fail("上传 Token 无效或已过期");
        }

        // MIME 校验（防绕过 Token 阶段的 MIME 校验）
        DarwinProperties.FileUpload cfg = darwinProperties.getFileUpload();
        String contentType = file.getContentType();
        if (contentType == null || !cfg.getAllowedMimeTypes().contains(contentType)) {
            return ApiResult.fail("不支持的文件类型: " + contentType);
        }

        // 文件大小校验
        long maxBytes = (long) cfg.getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            return ApiResult.fail("文件超过大小限制，最大 " + cfg.getMaxFileSizeMb() + "MB");
        }

        try {
            if (meta.getTraceId() != null) {
                MDC.put("traceId", meta.getTraceId());
            }
            String fileUrl = fileStorageService.store(file, meta.getBizType());
            log.info("[Darwin] 文件上传成功 correlationId={} bizType={} size={}",
                    meta.getCorrelationId(), meta.getBizType(), file.getSize());
            return ApiResult.ok(Map.of(
                    "fileUrl", fileUrl,
                    "correlationId", meta.getCorrelationId() != null ? meta.getCorrelationId() : ""
            ));
        } catch (Exception e) {
            log.error("[Darwin] 文件上传失败 correlationId={}", meta.getCorrelationId(), e);
            return ApiResult.fail("文件上传失败: " + e.getMessage());
        } finally {
            MDC.remove("traceId");
        }
    }
}
