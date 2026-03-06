package org.jeecg.modules.device.mqtt;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * MQTT消息协议体（Payload均使用per-device AES-256-CBC加密，消息体中不含任何鉴权信息）
 */
public class MqttMessageModel {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusReport {
        private BigDecimal temperature, humidity, batteryLevel;
        private Integer signalStrength, runStatus;
        private BigDecimal longitude, latitude;
        private long timestamp;
        private Map<String, Object> extra;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigPush {
        private String commandId;
        private Map<String, Object> params;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigAck {
        private String commandId;
        private int code;
        private String message;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemoteRestartCommand {
        private String commandId, reason;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestartAck {
        private String commandId, message;
        private int code;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtaNotify {
        private String taskId, recordId, firmwareId, version;
        private String downloadUrl, fileMd5;
        private Long fileSize;
        private Integer forceUpgrade;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtaProgress {
        private String taskId, recordId, failReason, newVersion;
        private Integer status, progress;
        private Long downloadedBytes;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationLogReport {
        private String operationType, operationDesc, operationDetail, operationResult;
        private long operationTime;
    }
}
