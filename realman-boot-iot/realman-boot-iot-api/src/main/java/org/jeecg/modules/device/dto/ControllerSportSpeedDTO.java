package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 主控端运动与安全参数 DTO（底盘速度/升降速度）
 */
@Data
public class ControllerSportSpeedDTO {

    /**
     * 底盘行进速度等级（例如：0=慢、1=中、2=快）
     */
    private Integer moveSpeedLevel;

    /**
     * 身体升降速度等级（例如：0=慢、1=中、2=快）
     */
    private Integer liftSpeedLevel;

    /**
     * 操作员标识（用于审计）
     */
    private String operator;
}

