package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;

/** 训练场批量离线注册的单条记录。对应设备基座详细设计 3.1 第 1 条、3.4 offline-register/batch。 */
@Data
@Schema(description = "离线注册单条记录")
public class OfflineRegisterItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String deviceCode;

    @NotNull
    private DeviceType deviceType;

    private String deviceModel;

    @NotBlank
    private String tenantId;
}
