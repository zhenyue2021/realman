package org.jeecg.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通过设备编码停止遥操请求参数
 */
@Data
public class TeleopStopByCodeDTO {

    @NotBlank(message = "controllerCode 不能为空")
    @Schema(description = "主控设备编码")
    private String controllerCode;

    @NotBlank(message = "deviceCode 不能为空")
    @Schema(description = "机器人设备编码")
    private String deviceCode;

    @Schema(description = "操作人（可选）")
    private String operator;
}
