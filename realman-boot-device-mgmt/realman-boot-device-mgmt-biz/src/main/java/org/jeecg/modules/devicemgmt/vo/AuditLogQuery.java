package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "操作审计日志查询条件")
public class AuditLogQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String deviceId;

    private String operationType;

    /** normal / high / critical */
    private String auditLevel;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
