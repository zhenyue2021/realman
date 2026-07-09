package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** 对应 PRD 9.4.4 版本矩阵响应。 */
@Data
@Schema(description = "版本矩阵")
public class VersionMatrixResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "所有出现的版本号列表（升序）")
    private List<String> versions;

    @Schema(description = "版本号 -> 设备数量")
    private Map<String, Long> versionDistribution;

    @Schema(description = "固件包仓库中该机型最新稳定版本（risk_level=normal 的最高版本）")
    private String latestRepoVersion;

    private List<DeviceVersionRow> devices;
}
