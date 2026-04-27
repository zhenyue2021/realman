package org.jeecg.modules.device.darwin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "darwin.integration")
public class DarwinProperties {

    private boolean enabled = false;

    private FileUpload fileUpload = new FileUpload();

    @Data
    public static class FileUpload {
        private int maxFileSizeMb = 50;
        private List<String> allowedMimeTypes = List.of(
                "image/jpeg", "image/png", "image/gif",
                "video/mp4", "application/pdf");
        private List<String> allowedBizTypes = List.of(
                "WORKORDER_ATTACHMENT", "DEVICE_FILE");
        private String uploadBucket = "darwin-files";
        /** 预签名下载 URL 有效期（天） */
        private int urlExpireDays = 7;
    }
}
