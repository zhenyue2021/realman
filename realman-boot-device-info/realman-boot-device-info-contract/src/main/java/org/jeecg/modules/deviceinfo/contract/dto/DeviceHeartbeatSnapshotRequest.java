package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 心跳快照同步。对应 {@code POST /internal/device-info/heartbeat-snapshot}。
 *
 * <p>{@code resourceSnapshot} 透传给 OTA 前置资源校验使用（磁盘/内存/电源/网络等），
 * 字段结构由 OTA 平台自行解析，本契约不做强类型建模，避免设备基座与 OTA 的资源
 * 校验字段变更耦合到同一份契约。
 */
@Data
@Schema(description = "设备心跳快照")
public class DeviceHeartbeatSnapshotRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String deviceId;

    @Schema(description = "最近一次上报的 IP")
    private String ipAddress;

    @NotNull
    private LocalDateTime heartbeatAt;

    @Schema(description = "资源快照透传字段（磁盘/内存/电源/网络等），供 OTA 前置校验使用")
    private Map<String, Object> resourceSnapshot;
}
