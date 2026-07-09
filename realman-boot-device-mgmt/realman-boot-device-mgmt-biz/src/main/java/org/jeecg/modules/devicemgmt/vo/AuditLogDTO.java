package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Schema(description = "操作审计日志条目")
public class AuditLogDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String deviceId;

    private String operationType;

    private String operator;

    private String operatorTenantId;

    private String targetTenantId;

    private String auditLevel;

    private Map<String, Object> detail;

    private LocalDateTime createdAt;
}
