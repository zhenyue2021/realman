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

    /**
     * 数据处理集成的双写切换开关（对齐 V2 主设计文档第六章"双写过渡"迁移方式）：
     * false（默认）走既有 RocketMQ 路径；true 时改走 {@code DarwinHttpClient} 同步 HTTP 直连，
     * 不再经 {@code daily_GLN_PLATFORM} 主题收发。灰度验证通过、观察一个完整业务周期无异常后，
     * 再考虑下线 RocketMQ 相关生产者/消费者代码与独立 Broker 部署。
     *
     * <p>{@code enabled=false} 时本开关不生效（Darwin 集成整体未启用，两条路径都不会走）。
     */
    private boolean httpEnabled = false;

    private Http http = new Http();

    /** Darwin 平台调用我方入站 HTTP 端点时使用的认证配置。 */
    private Inbound inbound = new Inbound();

    /**
     * Darwin 平台真实 HTTP 契约（路径、字段、鉴权方式）本仓库无法访问，以下按 V2 设计文档
     * 假设的契约实现（{@code POST {baseUrl}/internal/data-processing/oss-auth|file-report|
     * device-status}），需要与达尔文平台侧对接确认后再调整，不代表已验证的真实接口。
     */
    @Data
    public static class Http {
        private String baseUrl = "";
        /** 出站请求鉴权头名称，真实方案待达尔文平台侧确认，先假设为固定 API Key 请求头。 */
        private String apiKeyHeader = "X-Darwin-Api-Key";
        private String apiKey = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
    }

    @Data
    public static class Inbound {
        /** 入站 API Key 请求头，例如 darwin.integration.inbound.api-key-header=X-Darwin-Api-Key。 */
        private String apiKeyHeader = "X-Darwin-Api-Key";
        /** 入站 API Key 明文；为空时不启用 API Key 校验。 */
        private String apiKey = "";
        /** 入站 HMAC 密钥；为空时不启用 HMAC 校验。 */
        private String hmacSecret = "";
        /** 入站 HMAC 时间戳请求头，Unix epoch milliseconds。 */
        private String timestampHeader = "X-Darwin-Timestamp";
        /** 入站 HMAC 签名请求头，hex(HMAC-SHA256(canonicalString, hmacSecret))。 */
        private String signatureHeader = "X-Darwin-Signature";
        /** HMAC 防重放时间窗口，默认 5 分钟。 */
        private long timestampWindowSeconds = 300;
    }

    /**
     * Darwin 工单开启后多少分钟无操作则系统自动提交（含 MQTT 停采 + 机器人通知）。
     * 0 表示禁用自动提交。支持 Nacos 动态刷新。
     */
    private int autoSubmitMinutes = 10;

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
