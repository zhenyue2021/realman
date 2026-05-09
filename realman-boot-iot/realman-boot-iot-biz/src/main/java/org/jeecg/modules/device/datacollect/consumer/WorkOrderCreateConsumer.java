package org.jeecg.modules.device.datacollect.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.datacollect.entity.WorkOrderMapping;
import org.jeecg.modules.device.datacollect.mapper.WorkOrderMappingMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
    private final WorkOrderMappingMapper mappingMapper;
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

        if (dto.getDarwinOrderId() == null || dto.getDarwinOrderId().isBlank()) {
            log.error("[DataCollect] 工单消息缺少 darwinOrderId payload={}", message);
            return;
        }

        if (dto.getTraceId() != null) {
            MDC.put("traceId", dto.getTraceId());
        }

        try {
            Long count = mappingMapper.selectCount(new LambdaQueryWrapper<WorkOrderMapping>()
                    .eq(WorkOrderMapping::getDarwinOrderId, dto.getDarwinOrderId()));
            if (count > 0) {
                log.info("[DataCollect] 工单已处理，跳过 darwinOrderId={}", dto.getDarwinOrderId());
                return;
            }

            workOrderService.createWorkOrderFromDarwin(dto);
            log.info("[DataCollect] 工单创建成功 darwinOrderId={}", dto.getDarwinOrderId());
        } catch (Exception e) {
            log.error("[DataCollect] 创建工单失败 darwinOrderId={}", dto.getDarwinOrderId(), e);
            throw new RuntimeException(e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
