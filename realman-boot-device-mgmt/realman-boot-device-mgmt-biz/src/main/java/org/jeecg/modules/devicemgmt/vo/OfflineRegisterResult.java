package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "离线注册单条结果")
public class OfflineRegisterResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceCode;

    private String deviceId;

    private boolean success;

    private String message;
}
