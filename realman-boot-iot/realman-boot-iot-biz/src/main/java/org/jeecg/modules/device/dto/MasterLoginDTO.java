package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 主控端登录上报：操作员登录主控设备时由客户端或平台调用，记录登录信息及当时关联的机器人
 */
@Data
public class MasterLoginDTO {
    /** 主控设备ID（iot_device.id）或设备编码 device_code，二选一 */
    private String deviceId;
    private String deviceCode;

    /** 主控设备网卡MAC地址（登录解析时用于反查设备） */
    private String macAddress;

    /** 操作员ID（如 sys_user.id） */
    private String operatorId;
    /** 操作员账号/姓名 */
    private String operatorName;

    /** 当时关联的机器人设备ID（可选） */
    private String associatedRobotId;
    /** 当时关联的机器人设备编码（可选） */
    private String associatedRobotCode;
}
