package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.MqSendHelper;
import org.jeecg.modules.device.datacollect.config.DataCollectIntegrationProperties;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.FileAddressReportMsg;
import org.jeecg.modules.device.datacollect.http.DarwinHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将机器人上报的 OSS 文件地址转发给数采平台（Teleop → Darwin）。发送失败不阻塞主流程。
 *
 * <p>{@code darwin.integration.http-enabled=true} 时改走 {@link DarwinHttpClient} 同步
 * HTTP 直连（异步执行），不再经 RocketMQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "darwin.integration", name = "enabled", havingValue = "true")
public class FileAddressReportProducer {

    private final MqSendHelper mqSendHelper;
    private final ObjectMapper objectMapper;
    private final DataCollectIntegrationProperties properties;

    @Autowired(required = false)
    private DarwinHttpClient darwinHttpClient;

    public void send(String tenant, String deviceCode, String workOrderId, String taskId,
                     String ossAddress, List<String> fileList, String traceId) {
        FileAddressReportMsg msg = FileAddressReportMsg.builder()
                .tenant(tenant)
                .deviceCode(deviceCode)
                .traceId(traceId)
                .eventTime(System.currentTimeMillis())
                .data(FileAddressReportMsg.MsgData.builder()
                        .workOrderId(workOrderId)
                        .taskId(taskId)
                        .ossAddress(ossAddress)
                        .fileList(fileList)
                        .build())
                .build();

        if (properties.isHttpEnabled()) {
            if (darwinHttpClient == null) {
                log.warn("[DataCollect][HTTP] DarwinHttpClient 未装配，跳过 OSS 地址上报 deviceCode={}", deviceCode);
                return;
            }
            darwinHttpClient.reportFileAddress(msg);
            return;
        }

        String destination = DataCollectConstant.MQ_TOPIC_FILE_REPORT
                + ":" + DataCollectConstant.MQ_TAG_REPORT;
        try {
            var springMessage = MessageBuilder.withPayload(objectMapper.writeValueAsString(msg))
                    .setHeader("deviceCode", deviceCode)
                    .build();
            mqSendHelper.asyncSend(destination, springMessage, getClass().getSimpleName(), (receipt, ex) -> {
                if (ex != null) {
                    log.warn("[DataCollect] OSS地址上报失败，不阻塞主流程 deviceCode={}", deviceCode, ex);
                } else {
                    log.info("[DataCollect] OSS地址已上报至数采平台 deviceCode={} fileCount={} msgId={}",
                            deviceCode, fileList != null ? fileList.size() : 0, receipt.getMessageId());
                }
            });
        } catch (Exception e) {
            log.warn("[DataCollect] OSS地址上报序列化失败，不阻塞主流程 deviceCode={}", deviceCode, e);
            mqSendHelper.logSendFailure(destination, null, getClass().getSimpleName(), traceId, e);
        }
    }
}
