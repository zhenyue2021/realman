package org.jeecg.modules.device.vo;

import lombok.Data;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.entity.IotDeviceOperationLog;
import org.jeecg.modules.device.entity.IotDeviceStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理平台设备详情聚合视图：
 * - 设备基础信息（iot_device）
 * - 在线/离线与实时状态（Redis）
 * - 设备参数配置（iot_device_config）
 * - 最近一次状态上报（iot_device_status）
 * - 最近操作日志（iot_device_operation_log）
 */
@Data
public class DeviceDetailVO {
    /** 设备基础信息 */
    private IotDeviceDetail device;

    /** 是否在线（Redis在线集合） */
    private Boolean online;

    /** 最近一次心跳/上报时间（优先Redis实时状态中的timestamp，其次DB最新记录） */
    private LocalDateTime lastHeartbeatTime;

    /** 实时状态（Redis解密后的状态报文转Map，包含温湿度/电量/信号/定位/运行状态等） */
    private Map<String, Object> realtimeStatus;

    /** 设备参数配置列表（config_key / config_value / sync_status / sync_time 等） */
    private List<IotDeviceConfig> deviceConfigs;

    /** 最近一次状态记录（DB） */
    private IotDeviceStatus latestStatus;

    /** 最近操作日志（默认取最近20条） */
    private List<IotDeviceOperationLog> recentLogs;
}

