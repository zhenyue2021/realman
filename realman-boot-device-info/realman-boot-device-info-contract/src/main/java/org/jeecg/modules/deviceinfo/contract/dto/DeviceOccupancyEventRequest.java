package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyState;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 四态占用同步。对应 {@code POST /internal/device-info/occupancy-event}，
 * 由设备通信中台在遥操/自主控制状态变化时触发。
 */
@Data
@Schema(description = "设备四态占用事件")
public class DeviceOccupancyEventRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String deviceId;

    @NotNull
    private OccupancyState occupancyState;

    @Schema(description = "OCCUPIED 态细分，其余状态可为空")
    private OccupancyDetail occupancyDetail;

    @NotNull
    private LocalDateTime occurredAt;
}
