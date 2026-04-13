package org.jeecg.common.trace;

/**
 * 链路追踪上下文常量
 *
 * <p>统一定义 HTTP Header 名称与 MDC Key，避免各模块硬编码字符串。
 * 所有涉及 traceId 传播的组件（Filter、Feign 拦截器、MQTT Dispatcher 等）均引用此类常量。
 */
public final class TraceIdConst {

    // ===== HTTP Header =====
    /** 链路追踪 ID 请求/响应头，Gateway → 下游服务透传 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** Span ID 请求头（MQTT User Properties 透传时使用） */
    public static final String HEADER_SPAN_ID  = "X-Span-Id";

    // ===== MDC Key =====
    /** traceId MDC Key，Logback pattern 中通过 %X{traceId} 引用 */
    public static final String MDC_TRACE_ID = "traceId";
    /** spanId MDC Key */
    public static final String MDC_SPAN_ID  = "spanId";
    /** 服务名 MDC Key（spring.application.name） */
    public static final String MDC_SERVICE  = "service";
    /** 实例标识 MDC Key（hostname） */
    public static final String MDC_INSTANCE = "instance";
    /** 请求来源 MDC Key：http / mqtt / async / job */
    public static final String MDC_SOURCE   = "source";

    private TraceIdConst() {
    }
}
