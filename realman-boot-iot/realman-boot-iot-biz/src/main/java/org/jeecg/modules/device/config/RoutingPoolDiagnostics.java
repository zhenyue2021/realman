package org.jeecg.modules.device.config;

import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 路由池卡死时的线程栈诊断。
 */
@Slf4j
public final class RoutingPoolDiagnostics {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RoutingPoolDiagnostics() {
    }

    /**
     * 输出 device-task-* / device-keepalive 线程栈到 WARN 日志，并尽力写入 {@code logs/} 目录。
     */
    public static void dumpStuckWorkers(String reason) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = mx.dumpAllThreads(true, true);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("reason=").append(reason).append('\n');
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            String name = info.getThreadName();
            if (!name.startsWith("device-task-")
                    && !name.startsWith("device-keepalive")
                    && !name.startsWith("MQTT Call:")) {
                continue;
            }
            count++;
            sb.append('\n').append('"').append(name).append('"')
                    .append(" id=").append(info.getThreadId())
                    .append(" state=").append(info.getThreadState()).append('\n');
            for (StackTraceElement frame : info.getStackTrace()) {
                sb.append("\tat ").append(frame).append('\n');
            }
        }
        log.warn("[RoutingPool] 线程栈诊断 (matchedThreads={}):\n{}", count, sb);

        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("routing-pool-stall-" + FILE_TS.format(LocalDateTime.now()) + ".txt");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            log.warn("[RoutingPool] 线程栈已写入 {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[RoutingPool] 线程栈写文件失败", e);
        }
    }
}
