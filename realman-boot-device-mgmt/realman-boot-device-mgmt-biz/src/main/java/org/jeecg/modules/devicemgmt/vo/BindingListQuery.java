package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "绑定关系查询条件")
public class BindingListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String masterDeviceId;

    private String slaveDeviceId;

    private String tenantId;

    /** ACTIVE / REVOKED，缺省只查 ACTIVE */
    private String status = "ACTIVE";
}
