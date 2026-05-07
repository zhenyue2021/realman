package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("iot_device_command_record")
public class IotDeviceCommandRecord implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("command_id")
    private String commandId;

    @TableField("device_id")
    private String deviceId;

    @TableField("device_code")
    private String deviceCode;

    @TableField("command_type")
    private String commandType;

    /** device（机器人） / master（主控） */
    @TableField("device_type")
    private String deviceType;

    /** PENDING / SUCCESS / FAIL / TIMEOUT */
    @TableField("status")
    private String status;

    @TableField("fail_reason")
    private String failReason;

    @TableField("operator")
    private String operator;

    /** 下发指令的明文 JSON，不存 AES 密文 */
    @TableField("params_json")
    private String paramsJson;

    /** 设备回复的完整明文 JSON */
    @TableField("ack_data_json")
    private String ackDataJson;

    @TableField("send_time")
    private LocalDateTime sendTime;

    /** ACK 到达时间，可与 sendTime 差值计算 RTT */
    @TableField("ack_time")
    private LocalDateTime ackTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
