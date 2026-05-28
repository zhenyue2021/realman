package org.jeecg.modules.device.datacollect.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jeecg.modules.device.entity.IotMqMessageLog;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 拦截所有 @RocketMQMessageListener 类的 consume() 方法，自动记录消费日志。
 * 使用 ByteBuffer.duplicate() 读取消息体，不影响消费者本身的消息读取。
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class MqConsumerLogAspect {

    private final MqMessageLogService mqMessageLogService;
    private final ObjectMapper objectMapper;

    @Around("@within(listenerAnno) && execution(* consume(..))")
    public Object aroundConsume(ProceedingJoinPoint pjp,
                                RocketMQMessageListener listenerAnno) throws Throwable {
        long start = System.currentTimeMillis();

        IotMqMessageLog record = new IotMqMessageLog()
                .setDirection(2)
                .setTopic(listenerAnno.topic())
                .setTag(listenerAnno.tag())
                .setConsumerGroup(listenerAnno.consumerGroup())
                .setCallerClass(pjp.getTarget().getClass().getSimpleName());

        // duplicate() 读取消息体，不移动原 ByteBuffer 的 position，不影响消费者
        if (pjp.getArgs().length > 0 && pjp.getArgs()[0] instanceof MessageView mv) {
            String body = StandardCharsets.UTF_8.decode(mv.getBody().duplicate()).toString();
            record.setMessageBody(body)
                  .setMessageId(String.valueOf(mv.getMessageId()))
                  .setTraceId(extractTraceId(body));
        }

        try {
            Object result = pjp.proceed();
            if (result instanceof ConsumeResult cr && cr != ConsumeResult.SUCCESS) {
                record.setStatus(2).setFailReason("ConsumeResult=" + cr);
            } else {
                record.setStatus(1);
            }
            return result;
        } catch (Throwable t) {
            record.setStatus(2).setFailReason(truncate(t.getMessage(), 500));
            throw t;
        } finally {
            record.setCostTime(System.currentTimeMillis() - start);
            // consumer 的 finally 已执行（包含 MDC.remove），traceId 已通过 body 解析存入 record
            mqMessageLogService.asyncSave(record);
        }
    }

    private String extractTraceId(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode traceNode = node.get("traceId");
            return (traceNode != null && !traceNode.isNull()) ? traceNode.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
