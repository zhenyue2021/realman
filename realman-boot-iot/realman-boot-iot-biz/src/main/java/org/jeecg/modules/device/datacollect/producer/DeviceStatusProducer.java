package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.DeviceStatusMsg;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class DeviceStatusProducer {

    private final RocketMQClientTemplate rocketMQClientTemplate;
    private final ObjectMapper objectMapper;

    public void sendOnlineEvent(String tenant, String deviceCode, String deviceType,
                                String deviceModel, String traceId) {
        send(tenant, deviceCode, deviceType, deviceModel, DataCollectConstant.MQ_EVENT_ONLINE, "", traceId);
    }

    public void sendOfflineEvent(String tenant, String deviceCode, String deviceType,
                                 String deviceModel, String offlineReason, String traceId) {
        log.info("[Offline] - 检测到设备离线 deviceCode={} offlineReason={}", deviceCode, offlineReason);
        send(tenant, deviceCode, deviceType, deviceModel, DataCollectConstant.MQ_EVENT_OFFLINE,
                offlineReason == null ? "" : offlineReason, traceId);
    }

    private void send(String tenant, String deviceCode, String deviceType, String deviceModel,
                      String eventType, String offlineReason, String traceId) {
        DeviceStatusMsg msg = DeviceStatusMsg.builder()
                .tenant(tenant)
                .deviceCode(deviceCode)
                .traceId(traceId)
                .eventTime(System.currentTimeMillis())
                .data(DeviceStatusMsg.MsgData.builder()
                        .deviceType(deviceType)
                        .deviceModel(deviceModel)
                        .eventType(eventType)
                        .offlineReason(offlineReason)
                        .build())
                .build();
        try {
            String destination = DataCollectConstant.MQ_TOPIC_DEVICE_STATUS
                    + ":" + DataCollectConstant.MQ_TAG_DEVICE_STATUS;
            rocketMQClientTemplate.syncSendNormalMessage(destination, objectMapper.writeValueAsString(msg));
            log.info("[DataCollect] 设备状态推送成功 deviceCode={} event={}", deviceCode, eventType);
        } catch (Exception e) {
            log.warn("[DataCollect] 设备状态推送失败 deviceCode={} event={}", deviceCode, eventType, e);
        }
    }
}
