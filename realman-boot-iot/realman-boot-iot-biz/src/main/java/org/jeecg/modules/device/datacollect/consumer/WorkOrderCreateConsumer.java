package org.jeecg.modules.device.datacollect.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = DataCollectConstant.MQ_TOPIC_WORK_ORDER_IN,
        consumerGroup = DataCollectConstant.MQ_GROUP_WORK_ORDER_IN,
        selectorExpression = DataCollectConstant.MQ_TAG_CREATE
)
public class WorkOrderCreateConsumer implements RocketMQListener<String> {

    private final IWorkOrderService workOrderService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        WorkOrderCreateMsg dto;
        try {
            dto = objectMapper.readValue(message, WorkOrderCreateMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] 工单消息反序列化失败 payload={}", message, e);
            return;
        }

        if (dto.getData() == null || dto.getData().isEmpty()) {
            log.warn("[DataCollect] 工单消息 data 为空，跳过");
            return;
        }

        if (dto.getTraceId() != null) {
            MDC.put("traceId", dto.getTraceId());
        }

        try {
            String tenant = dto.getTenant() != null ? dto.getTenant() : "";
            List<WorkOrderCreateMsg.WorkOrderItem> items = dto.getData();
            for (WorkOrderCreateMsg.WorkOrderItem item : items) {
                if (item.getId() == null || item.getId().isBlank()) {
                    log.warn("[DataCollect] 工单项缺少 id，跳过 index={}", items.indexOf(item));
                    continue;
                }
                try {
                    processItem(tenant, item, dto.getTraceId());
                } catch (Exception e) {
                    log.error("[DataCollect] 工单处理失败 darwinOrderId={}", item.getId(), e);
                    throw e;
                }
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    private void processItem(String tenant, WorkOrderCreateMsg.WorkOrderItem item, String traceId) {
        String darwinOrderId = item.getId();

        // deleted=true：软删除已存在的工单（映射不存在时静默跳过）
        if ("true".equalsIgnoreCase(item.getDeleted())) {
            workOrderService.deleteWorkOrderFromDarwin(darwinOrderId);
            return;
        }

        // upsert：mapping 存在则更新，不存在则新建
        workOrderService.upsertWorkOrderFromDarwin(tenant, item, traceId);
    }
}
