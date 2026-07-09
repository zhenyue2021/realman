package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 测试标记同步。对应 {@code PUT /internal/device-info/{deviceId}/test-flag}，
 * 由设备管理业务平台在完成审计/二次确认后调用。
 */
@Data
@Schema(description = "测试设备标记同步请求")
public class TestFlagUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private Boolean testDevice;
}
