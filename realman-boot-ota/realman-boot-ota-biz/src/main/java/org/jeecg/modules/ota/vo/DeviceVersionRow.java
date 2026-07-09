package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "版本矩阵单设备行")
public class DeviceVersionRow implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;

    private String deviceCode;

    private String currentVersion;

    private boolean online;

    /** none / warn / critical，与同机型已注册设备 current_version 最大值对比 */
    private String versionLagLevelCluster;

    /** none / warn / critical，与固件仓库该机型最新稳定版本对比 */
    private String versionLagLevelRepo;

    private String lagReason;
}
