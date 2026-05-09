package org.jeecg.modules.device.datacollect.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.FileReportMsg;
import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;
import org.jeecg.modules.device.service.workorder.IWorkOrderAttachmentService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = DataCollectConstant.MQ_TOPIC_FILE_REPORT_IN,
        consumerGroup = DataCollectConstant.MQ_GROUP_FILE_REPORT_IN,
        selectorExpression = DataCollectConstant.MQ_TAG_UPLOAD
)
public class FileReportConsumer implements RocketMQListener<String> {

    private final IWorkOrderAttachmentService attachmentService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        FileReportMsg dto;
        try {
            dto = objectMapper.readValue(message, FileReportMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] 文件上报消息反序列化失败 payload={}", message, e);
            return;
        }

        if (dto.getDarwinFileId() == null || dto.getDarwinFileId().isBlank()) {
            log.error("[DataCollect] 文件上报消息缺少 darwinFileId payload={}", message);
            return;
        }

        if (dto.getTraceId() != null) {
            MDC.put("traceId", dto.getTraceId());
        }

        try {
            String dedupKey = DataCollectConstant.REDIS_DARWIN_FILE_DEDUP + dto.getDarwinFileId();
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 24, TimeUnit.HOURS);
            if (Boolean.FALSE.equals(isNew)) {
                log.info("[DataCollect] 文件上报已处理，跳过 darwinFileId={}", dto.getDarwinFileId());
                return;
            }

            if (dto.getWorkOrderId() != null && !dto.getWorkOrderId().isBlank()) {
                saveWorkOrderAttachment(dto);
            } else {
                log.warn("[DataCollect] 文件上报无 workOrderId，跳过附件关联 darwinFileId={}", dto.getDarwinFileId());
            }

            log.info("[DataCollect] 文件上报处理成功 darwinFileId={} workOrderId={}",
                    dto.getDarwinFileId(), dto.getWorkOrderId());
        } catch (Exception e) {
            log.error("[DataCollect] 文件上报处理失败 darwinFileId={}", dto.getDarwinFileId(), e);
            throw new RuntimeException(e);
        } finally {
            MDC.remove("traceId");
        }
    }

    private void saveWorkOrderAttachment(FileReportMsg dto) {
        WorkOrderAttachment attachment = new WorkOrderAttachment();
        attachment.setWorkOrderId(dto.getWorkOrderId());
        attachment.setFileName(dto.getFileName());
        attachment.setFileUrl(dto.getFileUrl());
        attachment.setDescription("达尔文平台上传 darwinFileId=" + dto.getDarwinFileId());
        attachment.setCreateTime(LocalDateTime.now());
        attachmentService.addAttachments(dto.getWorkOrderId(), "darwin", List.of(attachment));
    }
}
