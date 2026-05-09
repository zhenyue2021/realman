package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Teleop → Darwin（RocketMQ）：转发机器人 OSS 文件地址上报 */
@Data
@Builder
public class FileAddressReportMsg {
    private String traceId;
    private String deviceCode;
    /** 关联工单 ID，工单集成完成前可为 null */
    private String workOrderId;
    /** 采集任务 ID，工单集成完成前可为 null */
    private String taskId;
    private String ossAddress;
    private List<String> fileList;
    private long timestamp;
}
