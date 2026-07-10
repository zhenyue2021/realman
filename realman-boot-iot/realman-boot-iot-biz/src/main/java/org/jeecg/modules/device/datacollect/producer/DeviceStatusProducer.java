package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.MqSendHelper;
import org.jeecg.modules.device.datacollect.config.DataCollectIntegrationProperties;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.DeviceStatusMsg;
import org.jeecg.modules.device.datacollect.http.DarwinHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 推送设备上下线状态给数采平台（Teleop → Darwin）。
 *
 * <p>{@code darwin.integration.http-enabled=true} 时改走 {@link DarwinHttpClient} 同步
 * HTTP 直连（异步执行），不再经 RocketMQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class DeviceStatusProducer {

    private final MqSendHelper mqSendHelper;
    private final ObjectMapper objectMapper;
    private final DataCollectIntegrationProperties properties;

    @Autowired(required = false)
    private DarwinHttpClient darwinHttpClient;

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

        if (properties.isHttpEnabled()) {
            if (darwinHttpClient == null) {
                log.warn("[DataCollect][HTTP] DarwinHttpClient 未装配，跳过设备状态推送 deviceCode={} event={}", deviceCode, eventType);
                return;
            }
            darwinHttpClient.reportDeviceStatus(msg);
            return;
        }

        String destination = DataCollectConstant.MQ_TOPIC_DEVICE_STATUS
                + ":" + DataCollectConstant.MQ_TAG_DEVICE_STATUS;
        try {
            var springMessage = MessageBuilder.withPayload(objectMapper.writeValueAsString(msg))
                    .setHeader("deviceCode", deviceCode)
                    .build();
            mqSendHelper.asyncSend(destination, springMessage, getClass().getSimpleName(), (receipt, ex) -> {
                if (ex != null) {
                    log.warn("[DataCollect] 设备状态推送失败 deviceCode={} event={}", deviceCode, eventType, ex);
                } else {
                    log.info("[DataCollect] 设备状态推送成功 deviceCode={} event={} msgId={}",
                            deviceCode, eventType, receipt.getMessageId());
                }
            });
        } catch (Exception e) {
            log.warn("[DataCollect] 设备状态推送序列化失败 deviceCode={} event={}", deviceCode, eventType, e);
            mqSendHelper.logSendFailure(destination, null, getClass().getSimpleName(), traceId, e);
        }
    }
}
