package org.jeecg.modules.device.datacollect.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public void send(String deviceCode, String workOrderId, String taskId,
                     String ossAddress, List<String> fileList, String traceId) {
        FileAddressReportMsg msg = FileAddressReportMsg.builder()
                .traceId(traceId)
                .deviceCode(deviceCode)
                .workOrderId(workOrderId)
                .taskId(taskId)
                .ossAddress(ossAddress)
                .fileList(fileList)
                .timestamp(System.currentTimeMillis())
                .build();

        String destination = DataCollectConstant.MQ_TOPIC_FILE_REPORT
                + ":" + DataCollectConstant.MQ_TAG_REPORT;
        try {
            rocketMQTemplate.send(destination,
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(msg)).build());
            log.info("[DataCollect] OSS地址已上报至数采平台 deviceCode={} fileCount={}",
                    deviceCode, fileList != null ? fileList.size() : 0);
        } catch (Exception e) {
            log.warn("[DataCollect] OSS地址上报失败，不阻塞主流程 deviceCode={}", deviceCode, e);
        }
    }
}
