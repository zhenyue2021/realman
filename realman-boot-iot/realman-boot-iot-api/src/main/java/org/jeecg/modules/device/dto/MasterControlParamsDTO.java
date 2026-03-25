package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 主控端参数设置 DTO：
 * 力反馈（机械臂/夹爪力度） + 运动与安全参数（底盘/升降速度）一次性提交。
 */
@Data
public class MasterControlParamsDTO {

    /** 机器人设备ID */
    private String deviceId;
    /** 主控设备ID */
    private String controllerId;

    /** 机械臂力度等级 */
    private Integer armLevel;

    /** 夹爪力度等级 */
    private Integer gripperLevel;

    /** 底盘行进速度等级 */
    private Integer moveSpeedLevel;

    /** 身体升降速度等级 */
    private Integer liftSpeedLevel;

    /** 操作员标识（用于审计） */
    private String operator;
}

