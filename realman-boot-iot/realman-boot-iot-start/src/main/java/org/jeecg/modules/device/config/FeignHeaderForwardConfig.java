package org.jeecg.modules.device.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.common.constant.CommonConstant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 调用时透传当前请求的鉴权/租户头，避免下游服务 token 为空导致 401。
 *
 * 注意：仅在存在 HTTP 请求上下文时生效；定时任务/后台线程不会强行注入 token。
 */
@Configuration
public class FeignHeaderForwardConfig {

    @Bean
    public RequestInterceptor forwardHeadersInterceptor() {
        return template -> {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
                return;
            }
            HttpServletRequest request = servletAttrs.getRequest();
            if (request == null) {
                return;
            }

            // 1) 透传 X-Access-Token（jeecg/realman 默认鉴权头）
            String token = request.getHeader(CommonConstant.X_ACCESS_TOKEN);
            if (token != null && !token.isBlank()) {
                template.header(CommonConstant.X_ACCESS_TOKEN, token);
            }

            // 2) 常见的租户头/来源头（若存在则透传）
            forwardIfPresent(template, request, "tenant-id");
            forwardIfPresent(template, request, "X-Tenant-Id");
            forwardIfPresent(template, request, "X-Request-From");
            forwardIfPresent(template, request, "Authorization");
        };
    }

    private static void forwardIfPresent(feign.RequestTemplate template, HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank()) {
            template.header(headerName, value);
        }
    }
}

