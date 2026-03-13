package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 主控端力反馈参数 DTO（机械臂/夹爪力度）
 */
@Data
public class ControllerForceFeedbackDTO {

    /**
     * 机械臂力度等级（例如：0=关、1=轻、2=中、3=重、自定义可用更大值）
     */
    private Integer armLevel;

    /**
     * 夹爪力度等级（例如：0=关、1=轻、2=中、3=重、自定义可用更大值）
     */
    private Integer gripperLevel;

    /**
     * 操作员标识（用于审计）
     */
    private String operator;
}

