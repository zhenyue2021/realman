package org.jeecg.modules.device.config;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jeecg.common.trace.TraceIdConst;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;

/**
 * XXL-Job 定时任务链路追踪切面
 *
 * <p>职责：XXL-Job 的 {@code JobThread} 完全运行在 Servlet 容器之外，
 * {@link org.jeecg.common.trace.TraceIdMdcFilter} 无法拦截其线程。
 * 本切面在每个 {@link XxlJob} 方法执行前向 MDC 注入 traceId，
 * 并在执行结束后清理 MDC，确保日志带有完整的链路上下文。
 *
 * <p>traceId 格式：{@code job-{jobName}-{epochMilli}}，例如 {@code job-flushRobotStatusJob-1744511234567}，
 * 便于在 Grafana 中按 job 名称聚合查询。
 */
@Slf4j
@Aspect
@Component
public class XxlJobTracingAspect {

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    /**
     * 环绕切 @XxlJob 标注的所有方法
     *
     * @param pjp    连接点
     * @param xxlJob 目标方法上的 @XxlJob 注解（Spring AOP 自动绑定）
     */
    @Around("@annotation(xxlJob)")
    public Object traceJobExecution(ProceedingJoinPoint pjp, XxlJob xxlJob) throws Throwable {
        String jobName = xxlJob.value();
        String traceId = "job-" + jobName + "-" + Instant.now().toEpochMilli();

        MDC.put(TraceIdConst.MDC_TRACE_ID, traceId);
        MDC.put(TraceIdConst.MDC_SERVICE,   serviceName);
        MDC.put(TraceIdConst.MDC_INSTANCE,  resolveHostName());
        MDC.put(TraceIdConst.MDC_SOURCE,    "job");

        try {
            return pjp.proceed();
        } finally {
            // ⚠️ 必须清理：JobThread 是线程池复用线程，不清理会污染下一次任务
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
