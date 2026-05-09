package org.jeecg.modules.device.datacollect.dto.mq;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileReportMsg {

    private String traceId;
    /** 达尔文文件 ID（幂等 Key） */
    private String darwinFileId;
    /** 关联 OSS 授权请求的 correlationId */
    private String correlationId;
    /** 关联工单（可选） */
    private String workOrderId;
    /** 关联设备（可选） */
    private String deviceCode;
    /** ATTACHMENT / IMAGE / VIDEO */
    private String fileType;
    private String fileName;
    private String fileUrl;
    private long fileSize;
    private LocalDateTime uploadTime;
}
