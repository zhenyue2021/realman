package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 固件版本回写。对应 {@code PUT /internal/device-info/{deviceId}/firmware-version}，
 * 由 OTA 平台在升级成功后调用，是版本矩阵/版本落后判定的数据来源。
 */
@Data
@Schema(description = "固件版本回写请求")
public class FirmwareVersionUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "master/slave 单一版本号，统一大写 V 格式；Smart Arm 场景可为空，改用 firmwareComponents")
    private String firmwareVersion;

    @Schema(description = "多组件版本（Smart Arm 专用：app/model/fw）")
    private Map<String, String> firmwareComponents;
}
