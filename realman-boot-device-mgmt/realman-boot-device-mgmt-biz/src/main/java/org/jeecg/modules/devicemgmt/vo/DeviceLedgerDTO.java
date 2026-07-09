package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 台账聚合视图：SSOT 只读基础信息 + 本层的凭证状态/绑定关系。
 * 对齐设备基座详细设计 3.4 "台账与审计"。
 */
@Data
@Schema(description = "设备台账聚合视图")
public class DeviceLedgerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "SSOT 基础信息投影")
    private DeviceInfoDTO device;

    @Schema(description = "当前 MQTT 连接层密钥版本号，null 表示尚未签发")
    private Integer deviceSecretVersion;

    @Schema(description = "ACTIVE / REVOKED / EXPIRED / NONE")
    private String tokenStatus;

    private LocalDateTime tokenExpiresAt;

    @Schema(description = "当前生效的绑定关系（本层权威记录）")
    private List<BindingDTO> bindings;
}
