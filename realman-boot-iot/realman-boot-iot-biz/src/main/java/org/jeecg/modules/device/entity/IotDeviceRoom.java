package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * IoT 设备房间
 *
 * <p>生命周期：
 * <pre>
 *   主控 queryRoom(masterCode, robotCode) → 创建房间（status=ACTIVE，写入 master_code + robot_code）
 *   停止遥操/设备离线 → 销毁房间（status=DESTROYED，del_flag=1）
 * </pre>
 */
@Data
@TableName("iot_device_room")
public class IotDeviceRoom implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 房间号（主键，ASSIGN_ID 生成 32 位 UUID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** 主控设备编码 */
    @TableField("master_code")
    private String masterCode;

    /** 机器人设备编码（遥操开始时写入，销毁前有效） */
    @TableField("robot_code")
    private String robotCode;

    /**
     * 房间状态
     * <ul>
     *   <li>0 WAITING  - 已创建，等待机器人加入</li>
     *   <li>1 ACTIVE   - 主控与机器人均在房间内（遥操中）</li>
     *   <li>2 DESTROYED- 已销毁（停止遥操或设备离线）</li>
     * </ul>
     */
    @TableField("status")
    private Integer status;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 销毁时间（status 变为 DESTROYED 时写入） */
    @TableField("destroy_time")
    private LocalDateTime destroyTime;

    /** 逻辑删除：0=正常，1=已删除（销毁时同步置 1，保留历史记录） */
    @TableLogic
    @TableField("del_flag")
    private Integer delFlag;

    // ---- 状态常量 ----

    public interface Status {
        /** 等待机器人加入 */
        int WAITING   = 0;
        /** 双端在线，遥操进行中 */
        int ACTIVE    = 1;
        /** 已销毁 */
        int DESTROYED = 2;
    }
}
