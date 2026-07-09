package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 在线/离线事件。对应 {@code POST /internal/device-info/online-event}，
 * 由设备通信中台在处理 {@code $SYS/.../connected|disconnected} 后调用。
 */
@Data
@Schema(description = "设备上下线事件")
public class DeviceOnlineEventRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String deviceId;

    @NotNull
    @Schema(description = "ONLINE 或 OFFLINE；不接受 UNACTIVATED")
    private OnlineStatus eventType;

    @NotNull
    private LocalDateTime occurredAt;

    @Schema(description = "下线原因，如 KEEPALIVE_TIMEOUT；上线时可为空")
    private String offlineReason;
}
