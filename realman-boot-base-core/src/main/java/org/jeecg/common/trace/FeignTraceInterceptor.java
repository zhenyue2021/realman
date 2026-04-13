package org.jeecg.common.trace;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Feign 调用链路 Header 透传拦截器（P1）
 *
 * <p>职责：在 Feign 发起跨服务调用时，将当前线程 MDC 中的 traceId 写入请求头
 * {@code X-Trace-Id}，使下游服务的 {@link TraceIdMdcFilter} 能读取并延续同一链路。
 *
 * <p>Micrometer Tracing 已通过 B3 Multi Header（{@code X-B3-TraceId} 等）自动传播
 * 链路上下文；本拦截器额外透传 {@code X-Trace-Id}，供日志关联查询使用。
 *
 * <p>条件激活：仅在 classpath 存在 {@code feign.RequestInterceptor} 时注册 Bean，
 * 不使用 Feign 的服务模块不受影响。
 */
@Component
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TraceIdConst.MDC_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            template.header(TraceIdConst.HEADER_TRACE_ID, traceId);
        }
    }
}
