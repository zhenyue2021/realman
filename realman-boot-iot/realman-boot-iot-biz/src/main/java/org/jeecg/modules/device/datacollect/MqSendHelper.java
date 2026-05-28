package org.jeecg.modules.device.datacollect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.jeecg.modules.device.datacollect.log.MqMessageLogService;
import org.jeecg.modules.device.entity.IotMqMessageLog;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * RocketMQ 发送封装：统一管理 MDC 传播、MQ 日志记录和 CompletableFuture 生命周期。
 * Producer 通过此 Helper 发送消息，无需自行处理 MDC 和日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqSendHelper {

    private final RocketMQClientTemplate rocketMQClientTemplate;
    private final MqMessageLogService mqMessageLogService;

    /**
     * 异步发送普通消息，自动记录发送日志。
     *
     * @param destination   "TOPIC:TAG" 格式目标地址
     * @param springMessage Spring Message 消息体
     * @param callerClass   调用方简类名（用于日志溯源），传 {@code getClass().getSimpleName()}
     * @param onComplete    业务回调（仅含业务逻辑，无需处理 MDC；receipt 为 null 表示发送失败）
     */
    public void asyncSend(String destination,
                          Message<?> springMessage,
                          @Nullable String callerClass,
                          @Nullable BiConsumer<SendReceipt, Throwable> onComplete) {
        long start = System.currentTimeMillis();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        IotMqMessageLog record = new IotMqMessageLog()
                .setDirection(1)
                .setTopic(parseTopic(destination))
                .setTag(parseTag(destination))
                .setCallerClass(callerClass)
                .setMessageBody(extractBody(springMessage));

        CompletableFuture<SendReceipt> future = new CompletableFuture<>();
        try {
            rocketMQClientTemplate.asyncSendNormalMessage(destination, springMessage, future);
        } catch (Exception e) {
            record.setCostTime(System.currentTimeMillis() - start)
                    .setStatus(2)
                    .setFailReason(truncate(e.getMessage(), 500));
            mqMessageLogService.asyncSave(record);
            invokeOnComplete(onComplete, mdcContext, null, e);
            return;
        }

        future.whenComplete((receipt, ex) -> {
            if (mdcContext != null) MDC.setContextMap(mdcContext);
            try {
                record.setCostTime(System.currentTimeMillis() - start);
                if (ex == null) {
                    record.setStatus(1).setMessageId(receipt.getMessageId().toString());
                } else {
                    record.setStatus(2).setFailReason(truncate(ex.getMessage(), 500));
                }
                // MDC 已恢复，asyncSave 可从中捕获 traceId
                mqMessageLogService.asyncSave(record);
                if (onComplete != null) onComplete.accept(receipt, ex);
            } finally {
                MDC.clear();
            }
        });
    }

    /**
     * 记录发送失败日志（序列化失败等未进入 asyncSend 的场景）。
     */
    public void logSendFailure(String destination,
                               @Nullable String messageBody,
                               @Nullable String callerClass,
                               @Nullable String traceId,
                               Throwable cause) {
        IotMqMessageLog record = new IotMqMessageLog()
                .setDirection(1)
                .setTopic(parseTopic(destination))
                .setTag(parseTag(destination))
                .setMessageBody(messageBody)
                .setCallerClass(callerClass)
                .setTraceId(traceId)
                .setStatus(2)
                .setFailReason(truncate(cause.getMessage(), 500))
                .setCostTime(0L);
        mqMessageLogService.asyncSave(record);
    }

    private void invokeOnComplete(@Nullable BiConsumer<SendReceipt, Throwable> onComplete,
                                  @Nullable Map<String, String> mdcContext,
                                  @Nullable SendReceipt receipt,
                                  Throwable ex) {
        if (onComplete == null) {
            return;
        }
        if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
        }
        try {
            onComplete.accept(receipt, ex);
        } finally {
            MDC.clear();
        }
    }

    private String parseTopic(String destination) {
        int idx = destination.indexOf(':');
        return idx > 0 ? destination.substring(0, idx) : destination;
    }

    private String parseTag(String destination) {
        int idx = destination.indexOf(':');
        return idx > 0 ? destination.substring(idx + 1) : null;
    }

    private String extractBody(Message<?> msg) {
        Object payload = msg.getPayload();
        return payload instanceof String s ? s : String.valueOf(payload);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
