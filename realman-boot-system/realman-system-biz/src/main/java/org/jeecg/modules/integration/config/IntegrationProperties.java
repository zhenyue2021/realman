package org.jeecg.modules.integration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 外部集成模块配置
 * <p>对应 application.yml 中的 integration 节点
 */
@Data
@Component
@ConfigurationProperties(prefix = "integration")
public class IntegrationProperties {

    private TempToken tempToken = new TempToken();

    @Data
    public static class TempToken {
        /**
         * 内部服务账号用户名，用于为外部系统颁发临时 Token
         * <p>该账号仅用于 Token 颁发，不用于页面登录
         */
        private String serviceUsername = "yunwei";

        /**
         * 允许调用本接口的 sourceSystem 白名单，大小写不敏感
         */
        private List<String> allowedSourceSystems = List.of("DEW");
    }
}
