package org.jeecg.modules.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("iot_mq_message_log")
public class IotMqMessageLog implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** 消息方向：1=发送，2=接收 */
    @TableField("direction")
    private Integer direction;

    @TableField("topic")
    private String topic;

    @TableField("tag")
    private String tag;

    /** 消费者组，发送时为 null */
    @TableField("consumer_group")
    private String consumerGroup;

    @TableField("message_id")
    private String messageId;

    @TableField("message_body")
    private String messageBody;

    /** 发送方 / 消费方简类名 */
    @TableField("caller_class")
    private String callerClass;

    /** 状态：1=成功，2=失败 */
    @TableField("status")
    private Integer status;

    @TableField("fail_reason")
    private String failReason;

    /** 耗时（ms） */
    @TableField("cost_time")
    private Long costTime;

    @TableField("trace_id")
    private String traceId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("create_time")
    private Date createTime;
}
