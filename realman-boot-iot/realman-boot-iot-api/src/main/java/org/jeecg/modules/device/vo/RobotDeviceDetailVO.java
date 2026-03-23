package org.jeecg.modules.device.vo;

import lombok.Data;
import org.jeecg.modules.device.dto.DeviceConfigViewDTO;
import org.jeecg.modules.device.dto.DeviceOperationLogViewDTO;
import org.jeecg.modules.device.dto.DeviceStatusViewDTO;
import org.jeecg.modules.device.dto.RobotDevicePageItemDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 机器人设备详情聚合视图（前端响应，不直出持久化设备实体）
 */
@Data
public class RobotDeviceDetailVO {

    private RobotDevicePageItemDTO device;
    private Boolean online;
    private LocalDateTime lastHeartbeatTime;
    private Map<String, Object> realtimeStatus;
    private List<DeviceConfigViewDTO> deviceConfigs;
    private DeviceStatusViewDTO latestStatus;
    private List<DeviceOperationLogViewDTO> recentLogs;
}
