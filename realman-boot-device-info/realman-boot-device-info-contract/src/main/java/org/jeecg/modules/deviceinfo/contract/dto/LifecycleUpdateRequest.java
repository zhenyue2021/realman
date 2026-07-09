package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;

import java.io.Serializable;

/**
 * 生命周期阶段变更。对应 {@code PUT /internal/device-info/{deviceId}/lifecycle}，
 * 由设备管理业务平台运维操作触发（RUNNING / MAINTENANCE / RETIRED）。
 */
@Data
@Schema(description = "生命周期阶段变更请求")
public class LifecycleUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private LifecycleStage lifecycleStage;
}
