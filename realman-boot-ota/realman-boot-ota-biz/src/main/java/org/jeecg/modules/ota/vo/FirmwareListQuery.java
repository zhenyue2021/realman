package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "固件包列表查询条件，支持按设备类型/风险等级/签名状态筛选（PRD 4.2.3）")
public class FirmwareListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String deviceType;

    private String riskLevel;

    /** 按关联公钥状态筛选：active / pending_activation / revoked */
    private String keyStatus;
}
