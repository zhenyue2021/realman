package org.jeecg.modules.device.vo;

import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主控使用状态：最近登录时间、最近一次遥操开始时间、当前遥操机器人、可使用的机器人列表
 */
@Data
public class UsageStatusVO {

    /** 主控设备ID */
    private String controllerId;
    /** 主控设备编号 */
    private String controllerCode;

    /** 最近登录时间（主控最后一次被登录时间） */
    private LocalDateTime lastLoginTime;

    /** 最近一次遥操开始时间 */
    private LocalDateTime lastRemoteOperationStartTime;

    /** 当前设备：当前正在遥操的机器人状态（无则为 null） */
    private RobotBasicVO currentDevice;

    /** 可使用的机器人列表（与该主控绑定的机器人） */
    private List<RobotBasicVO> availableRobots;

    @Data
    public static class RobotBasicVO {
        private String robotId;
        private String robotCode;
        private String robotName;
        /** 状态：1-在线 2-离线 等 */
        @Dict(dicCode = "device_status")
        private Integer status;
        /** 电量等可从实时状态取，这里简单用字段 */
        private String batteryLevel;
        private String deviceModel;
        private String firmwareVersion;
    }
}
