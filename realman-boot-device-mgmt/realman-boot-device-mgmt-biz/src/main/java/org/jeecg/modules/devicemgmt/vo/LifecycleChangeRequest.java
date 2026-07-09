package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;

import java.io.Serializable;

/** 对应 PUT /api/v1/devices/{deviceId}/lifecycle。变更写入 SSOT 的投影同步走 Feign。 */
@Data
@Schema(description = "生命周期阶段变更请求（运维操作）")
public class LifecycleChangeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private LifecycleStage lifecycleStage;
}
