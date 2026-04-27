package org.jeecg.modules.device.darwin.dto;

import lombok.Data;

@Data
public class DarwinOssAuthRequestDTO {

    private String traceId;
    /** 达尔文自生成，用于匹配响应 */
    private String correlationId;
    private String fileName;
    private long fileSize;
    private String mimeType;
    /** WORKORDER_ATTACHMENT / DEVICE_FILE */
    private String bizType;
    /** 关联的达尔文业务 ID（工单 ID 或设备 Code） */
    private String bizId;
}
