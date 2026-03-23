package org.jeecg.modules.device.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备操作日志视图（详情聚合用）
 */
@Data
public class DeviceOperationLogViewDTO {

    private String id;
    private String deviceId;
    private String deviceCode;
    private String operationType;
    private String operationDesc;
    private String operationDetail;
    private String operationSource;
    private String operationResult;
    private String failReason;
    private String operator;
    private String clientIp;
    private LocalDateTime createTime;
    private LocalDateTime operationTime;
}
