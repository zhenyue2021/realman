package org.jeecg.modules.device.datacollect.dto.mqtt;

import lombok.Data;

import java.util.List;

/** 机器人 → 遥操平台：OSS 上传完成地址回传（设计文档 5.1.3.4） */
@Data
public class OssAddressReportMsg {
    private Long timestamp;
    private String deviceSn;
    private OssInfo oss;

    @Data
    public static class OssInfo {
        private String address;
        private String businessKey;
        private List<String> list;
    }
}
