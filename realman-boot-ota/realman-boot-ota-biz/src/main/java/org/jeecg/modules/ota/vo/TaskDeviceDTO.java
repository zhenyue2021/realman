package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "设备级升级子任务")
public class TaskDeviceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;

    private String deviceCode;

    /** 15 态之一 */
    private String state;

    private Integer progressPct;

    private String subStage;

    private String sigVerifyResult;

    private String upgradeErrorCode;

    private String upgradeErrorMsg;

    private String rollbackReason;

    private LocalDateTime reportedAt;
}
