package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 固件包上传表单字段（{@code multipart/form-data} 中除文件之外的部分），
 * 对应 PRD 9.1.1。
 */
@Data
@Schema(description = "固件包上传元数据")
public class FirmwareUploadMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Schema(description = "master / slave")
    private String deviceType;

    @Schema(description = "最低可升级版本，格式 V大.小.修，为空不做版本号校验")
    private String minVersion;

    @Schema(description = "适配设备型号，逗号分隔，为空适配全型号")
    private String compatibleModels;

    private String installCommand;

    private String rollbackCommand;

    private String healthcheckCommand;

    @Schema(description = "normal / high_risk，默认 normal")
    private String riskLevel = "normal";

    @Schema(description = "EXECUTING 阶段是否可安全中断，默认 false")
    private boolean cancelableInExecuting = false;

    @Schema(description = "LOCAL / OSS，默认 LOCAL；选择 OSS 时要求 ota.firmware.oss.enabled=true 已配置")
    private String storageMode = "LOCAL";
}
