package org.jeecg.modules.device.datacollect.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "darwin.integration")
public class DataCollectIntegrationProperties {

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
        private int urlExpireDays = 7;
    }
}
