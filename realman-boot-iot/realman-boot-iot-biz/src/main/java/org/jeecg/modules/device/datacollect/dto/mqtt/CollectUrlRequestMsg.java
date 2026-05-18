package org.jeecg.modules.device.datacollect.dto.mqtt;

import lombok.Data;

/** 机器人 → 遥操平台：请求 OSS 上传授权（设计文档 5.1.3.2） */
@Data
public class CollectUrlRequestMsg {
    private String requestId;
    private Long timestamp;
    private String deviceSn;
}
