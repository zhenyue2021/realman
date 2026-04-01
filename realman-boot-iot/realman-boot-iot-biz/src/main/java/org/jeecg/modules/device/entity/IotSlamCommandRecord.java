package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * SLAM 指令请求/响应记录
 *
 * <p>每次平台向设备发送 slam/request 指令时创建一条记录（status=PENDING），
 * 收到设备 slam/ack 后逐步更新：
 * <ul>
 *   <li>中间响应（sequence &lt; total）→ status=PARTIAL</li>
 *   <li>最终响应且成功（sequence==total, success=true）→ status=COMPLETED</li>
 *   <li>任意响应失败（success=false）→ status=FAILED</li>
 * </ul>
 */
@Data
@TableName("iot_slam_command_record")
public class IotSlamCommandRecord implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** 设备编码 */
    @TableField("device_code")
    private String deviceCode;

    /** 请求唯一标识（下发到设备的 commandId） */
    @TableField("command_id")
    private String commandId;

    /** 功能代码（SwitchMode / GetCurrentMap / SaveMap 等） */
    @JsonProperty("function")
    @TableField("function_name")
    private String functionName;

    /** 请求参数 JSON */
    @TableField("params_json")
    private String paramsJson;

    /** 记录状态：PENDING / PARTIAL / COMPLETED / FAILED */
    @TableField("status")
    private String status;

    /** 最新 ack 的 success 字段 */
    @TableField("ack_success")
    private Boolean ackSuccess;

    /** 最新 ack 的 code 字段 */
    @TableField("ack_code")
    private Integer ackCode;

    /** 最新 ack 的 message 字段 */
    @TableField("ack_message")
    private String ackMessage;

    /** 当前已收到的最大响应序号 */
    @TableField("ack_sequence")
    private Integer ackSequence;

    /** 本次请求预期总响应次数 */
    @TableField("ack_total")
    private Integer ackTotal;

    /** 最终 ack 的 data JSON（最后一次响应的 data 字段） */
    @TableField("ack_data_json")
    private String ackDataJson;

    /** 指令发送时间 */
    @TableField("send_time")
    private LocalDateTime sendTime;

    /** 完成时间（收到最终响应或失败响应） */
    @TableField("complete_time")
    private LocalDateTime completeTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
