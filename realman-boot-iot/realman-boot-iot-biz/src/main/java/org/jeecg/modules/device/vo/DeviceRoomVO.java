package org.jeecg.modules.device.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备房间视图对象（缓存序列化 & 接口响应复用）
 */
@Data
public class DeviceRoomVO {

    /** 房间号 */
    private String roomId;

    /** 主控设备编码 */
    private String masterCode;

    /** 机器人设备编码（遥操开始前为 null） */
    private String robotCode;

    /**
     * 房间状态：0=等待中 1=遥操中 2=已销毁
     *
     * @see org.jeecg.modules.device.entity.IotDeviceRoom.Status
     */
    private Integer status;

    /** 房间创建时间 */
    private LocalDateTime createTime;
}
