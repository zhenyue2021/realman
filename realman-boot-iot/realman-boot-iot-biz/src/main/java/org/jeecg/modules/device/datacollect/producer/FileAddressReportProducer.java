package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.apache.rocketmq.client.support.RocketMQHeaders;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.FileAddressReportMsg;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将机器人上报的 OSS 文件地址转发给数采平台（Teleop → Darwin）。
 * 发送失败不阻塞主流程，仅记录 warn 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileAddressReportProducer {

    private final RocketMQClientTemplate rocketMQClientTemplate;
    private final ObjectMapper objectMapper;

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

        String destination = DataCollectConstant.MQ_TOPIC_FILE_REPORT
                + ":" + DataCollectConstant.MQ_TAG_REPORT;
        try {
            var springMessage = MessageBuilder.withPayload(objectMapper.writeValueAsString(msg))
                    .setHeader(RocketMQHeaders.KEYS, deviceCode)
                    .build();
            rocketMQClientTemplate.syncSendNormalMessage(destination, springMessage);
            log.info("[DataCollect] OSS地址已上报至数采平台 deviceCode={} fileCount={}",
                    deviceCode, fileList != null ? fileList.size() : 0);
        } catch (Exception e) {
            log.warn("[DataCollect] OSS地址上报失败，不阻塞主流程 deviceCode={}", deviceCode, e);
        }
    }
}
