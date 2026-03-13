package org.jeecg.modules.device.vo;

import lombok.Data;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;

import java.util.List;

/**
 * 主控端登录后，同步解析“当前设备/绑定机器人”的返回
 */
@Data
public class MasterLoginResolveVO {

    /** 登录日志ID（写入 iot_controller_login_log 后返回） */
    private String loginLogId;

    /** 主控设备信息（device_type=2） */
    private IotDevice controller;

    /** 当前关联机器人（来自主控响应并校验通过） */
    private UsageStatusVO.RobotBasicVO currentRobot;

    /** 该主控在当前用户授权下可使用的机器人列表 */
    private List<UsageStatusVO.RobotBasicVO> availableRobots;

    /** 当前待开启的工单（按生效时间最近一条），无则为 null */
    private WorkOrder pendingWorkOrder;
}

