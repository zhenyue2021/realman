package org.jeecg.modules.device.darwin.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.jeecg.modules.device.darwin.constant.DarwinTopicConstant;
import org.jeecg.modules.device.darwin.dto.DarwinDeviceStatusDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class DarwinDeviceStatusProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public void sendOnlineEvent(String deviceCode, String deviceType, String traceId) {
        send(deviceCode, deviceType, DarwinTopicConstant.TAG_ONLINE, "", traceId);
    }

    public void sendOfflineEvent(String deviceCode, String deviceType, String offlineReason, String traceId) {
        send(deviceCode, deviceType, DarwinTopicConstant.TAG_OFFLINE,
                offlineReason == null ? "" : offlineReason, traceId);
    }

    private void send(String deviceCode, String deviceType, String tag, String offlineReason, String traceId) {
        DarwinDeviceStatusDTO dto = DarwinDeviceStatusDTO.builder()
                .traceId(traceId)
                .deviceCode(deviceCode)
                .deviceType(deviceType)
                .eventType(tag)
                .eventTime(System.currentTimeMillis())
                .offlineReason(offlineReason)
                .build();
        try {
            String destination = DarwinTopicConstant.DEVICE_STATUS + ":" + tag;
            rocketMQTemplate.send(destination,
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(dto)).build());
            log.info("[Darwin] 设备状态推送成功 deviceCode={} event={}", deviceCode, tag);
        } catch (Exception e) {
            // 推送失败不影响主流程，只记录告警
            log.warn("[Darwin] 设备状态推送失败 deviceCode={} event={}", deviceCode, tag, e);
        }
    }
}
