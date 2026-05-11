package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.DeviceStatusMsg;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class DeviceStatusProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public void sendOnlineEvent(String deviceCode, String deviceType, String traceId) {
        send(deviceCode, deviceType, DataCollectConstant.MQ_TAG_ONLINE, "", traceId);
    }

    public void sendOfflineEvent(String deviceCode, String deviceType, String offlineReason, String traceId) {
        log.info("[Offline] - 检测到设备离线 deviceCode={} offlineReason={}", deviceCode, offlineReason);
        send(deviceCode, deviceType, DataCollectConstant.MQ_TAG_OFFLINE,
                offlineReason == null ? "" : offlineReason, traceId);
    }

    private void send(String deviceCode, String deviceType, String tag, String offlineReason, String traceId) {
        DeviceStatusMsg msg = DeviceStatusMsg.builder()
                .traceId(traceId)
                .deviceCode(deviceCode)
                .deviceType(deviceType)
                .eventType(tag)
                .eventTime(System.currentTimeMillis())
                .offlineReason(offlineReason)
                .build();
        try {
            String destination = DataCollectConstant.MQ_TOPIC_DEVICE_STATUS + ":" + tag;
            rocketMQTemplate.send(destination,
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(msg)).build());
            log.info("[DataCollect] 设备状态推送成功 deviceCode={} event={}", deviceCode, tag);
        } catch (Exception e) {
            log.warn("[DataCollect] 设备状态推送失败 deviceCode={} event={}", deviceCode, tag, e);
        }
    }
}
