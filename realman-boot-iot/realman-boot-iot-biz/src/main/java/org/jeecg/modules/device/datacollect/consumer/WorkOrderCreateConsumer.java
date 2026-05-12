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
            for (WorkOrderCreateMsg.WorkOrderItem item : dto.getData()) {
                if (item.getId() == null || item.getId().isBlank()) {
                    log.warn("[DataCollect] 工单项缺少 id，跳过");
                    continue;
                }
                try {
                    if ("true".equalsIgnoreCase(item.getDeleted())) {
                        // deleted=true：按 item.id 软删除对应工单
                        workOrderService.deleteWorkOrderFromDarwin(item.getId());
                    } else {
                        // item.id 直接作为 work_order 主键，查到则更新，查不到则新建
                        workOrderService.upsertWorkOrderFromDarwin(item.getId(), tenant, item, dto.getTraceId());
                    }
                } catch (Exception e) {
                    log.error("[DataCollect] 工单处理失败 workOrderId={}", item.getId(), e);
                    throw e;
                }
            }
        } finally {
            MDC.remove("traceId");
        }
    }
}
