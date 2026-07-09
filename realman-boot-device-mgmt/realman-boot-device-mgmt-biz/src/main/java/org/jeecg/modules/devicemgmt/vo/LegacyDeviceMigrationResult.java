package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 存量设备迁移（{@code iot_device} → {@code device_info}/{@code device_credential}）结果汇总。 */
@Data
@Schema(description = "存量设备迁移结果")
public class LegacyDeviceMigrationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "legacy iot_device 扫描到的总行数（含重复 device_code 的历史行）")
    private int totalScanned;

    @Schema(description = "成功写入 device_info 的设备数（每个 device_code 只取一条代表行）")
    private int migratedDeviceCount;

    @Schema(description = "device_info 中已存在、跳过未重复迁移的设备数（幂等重跑时非零）")
    private int skippedAlreadyExists;

    @Schema(description = "同一 device_code 下的历史行数（未写入 device_info，仅落 device_operation_audit_log 留痕）")
    private int historyOnlyCount;

    @Schema(description = "迁移失败的设备数")
    private int failedCount;

    @Schema(description = "失败明细（deviceCode + 原因），最多保留前 200 条")
    private List<String> failureDetails = new ArrayList<>();
}
