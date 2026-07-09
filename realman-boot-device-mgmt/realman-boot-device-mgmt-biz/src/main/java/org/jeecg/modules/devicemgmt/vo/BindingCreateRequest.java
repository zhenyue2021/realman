package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 POST /api/v1/devices/bindings。V1 一对一：同一 masterDeviceId/slaveDeviceId 只能有一条 ACTIVE 绑定。 */
@Data
@Schema(description = "创建主控端-机器人绑定请求")
public class BindingCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String masterDeviceId;

    @NotBlank
    private String slaveDeviceId;

    @NotBlank
    private String tenantId;

    /** V1_ONE_TO_ONE / V2_MANY_TO_MANY，缺省 V1_ONE_TO_ONE */
    private String bindMode = "V1_ONE_TO_ONE";
}
