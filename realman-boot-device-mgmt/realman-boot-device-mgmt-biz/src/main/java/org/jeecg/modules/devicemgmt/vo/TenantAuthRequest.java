package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 对应 POST /api/v1/devices/{deviceId}/tenant-auth，超管操作，可能是跨租户授权。 */
@Data
@Schema(description = "设备-租户授权请求")
public class TenantAuthRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String tenantId;

    /** 有效期，缺省长期有效 */
    private LocalDateTime validUntil;
}
