package org.jeecg.modules.device.darwin.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jeecg.modules.device.darwin.constant.DarwinTopicConstant;
import org.jeecg.modules.device.darwin.dto.DarwinWorkOrderCreateDTO;
import org.jeecg.modules.device.darwin.entity.DarwinWorkOrderMapping;
import org.jeecg.modules.device.darwin.mapper.DarwinWorkOrderMappingMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = DarwinTopicConstant.WORK_ORDER_IN,
        consumerGroup = "DARWIN_WORKORDER_CONSUMER_GROUP",
        selectorExpression = DarwinTopicConstant.TAG_CREATE
)
public class DarwinWorkOrderConsumer implements RocketMQListener<String> {

    private final IWorkOrderService workOrderService;
    private final DarwinWorkOrderMappingMapper mappingMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        DarwinWorkOrderCreateDTO dto;
        try {
            dto = objectMapper.readValue(message, DarwinWorkOrderCreateDTO.class);
        } catch (Exception e) {
            log.error("[Darwin] 工单消息反序列化失败 payload={}", message, e);
            return; // 格式错误不重试，直接 ACK
        }

        if (dto.getDarwinOrderId() == null || dto.getDarwinOrderId().isBlank()) {
            log.error("[Darwin] 工单消息缺少 darwinOrderId payload={}", message);
            return;
        }

        if (dto.getTraceId() != null) {
            MDC.put("traceId", dto.getTraceId());
        }

        try {
            // 幂等：darwinOrderId 已存在则跳过
            Long count = mappingMapper.selectCount(new LambdaQueryWrapper<DarwinWorkOrderMapping>()
                    .eq(DarwinWorkOrderMapping::getDarwinOrderId, dto.getDarwinOrderId()));
            if (count > 0) {
                log.info("[Darwin] 工单已处理，跳过 darwinOrderId={}", dto.getDarwinOrderId());
                return;
            }

            workOrderService.createWorkOrderFromDarwin(dto);
            log.info("[Darwin] 工单创建成功 darwinOrderId={}", dto.getDarwinOrderId());
        } catch (Exception e) {
            log.error("[Darwin] 创建工单失败 darwinOrderId={}", dto.getDarwinOrderId(), e);
            // 抛出以触发 RocketMQ 重试（DB/网络等基础设施异常）
            throw new RuntimeException(e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
