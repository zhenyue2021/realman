package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "绑定关系")
public class BindingDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String masterDeviceId;

    private String slaveDeviceId;

    private String tenantId;

    private String bindMode;

    /** ACTIVE / REVOKED */
    private String status;

    private String createdBy;

    private LocalDateTime createdAt;
}
