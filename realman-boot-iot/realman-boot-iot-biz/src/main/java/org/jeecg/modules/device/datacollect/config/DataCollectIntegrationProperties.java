package org.jeecg.modules.device.datacollect.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "darwin.integration")
public class DataCollectIntegrationProperties {

    private boolean enabled = false;

    /** Darwin 工单默认部门 ID，写入 work_order.department_id */
    private String defaultDepartmentId = "";
    /** Darwin 工单默认代理商名称，写入 work_order.department_name */
    private String defaultDepartmentName = "";

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
