package org.jeecg.common.trace;

import cn.hutool.core.util.IdUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

/**
 * HTTP 链路 MDC 注入过滤器
 *
 * <p>职责：
 * <ol>
 *   <li>从请求头 {@code X-Trace-Id} 读取上游传入的 traceId；若不存在则生成新 UUID（32位，无连字符）。</li>
 *   <li>将 traceId / service / instance / source 写入 MDC，使 Logback 日志自动携带上下文字段。</li>
 *   <li>将 traceId 写入响应头，方便前端或 API 调用方关联排查。</li>
 *   <li>请求结束后在 finally 块清理 MDC，防止线程池复用时上下文污染。</li>
 * </ol>
 *
 * <p>优先级说明：{@code Order(-100)} 确保在 Shiro / Spring Security 过滤器之前执行，
 * 使鉴权相关日志也能携带 traceId。
 *
 * <p>与 Micrometer Tracing 的协作：Micrometer 会在 HTTP 请求处理期间自动向 MDC 写入
 * B3 格式的 {@code traceId} / {@code spanId}（覆盖本 Filter 生成的 UUID 格式 traceId）。
 * 本 Filter 额外补充的 {@code service} / {@code instance} / {@code source} 字段不受影响。
 */
@Component
@Order(-100)
public class TraceIdMdcFilter implements Filter {

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        // 1. 优先读取上游（Gateway 或客户端）传入的 traceId；不存在时自动生成
        String traceId = req.getHeader(TraceIdConst.HEADER_TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = IdUtil.fastSimpleUUID();
        }

        // 2. 写入 MDC —— Logback 每条日志自动携带
        MDC.put(TraceIdConst.MDC_TRACE_ID, traceId);
        MDC.put(TraceIdConst.MDC_SERVICE,  serviceName);
        MDC.put(TraceIdConst.MDC_INSTANCE, resolveHostName());
        MDC.put(TraceIdConst.MDC_SOURCE,   "http");

        // 3. 响应头回写，便于前端或 API 调用方关联日志
        res.setHeader(TraceIdConst.HEADER_TRACE_ID, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // ⚠️ 必须清理：Servlet 容器线程池复用时，若不清理则 MDC 会被下一个请求读到脏数据
            MDC.clear();
        }
    }

    private String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
