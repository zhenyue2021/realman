package org.jeecg.modules.commhub.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一上行事件落库记录，供第三方轮询兜底查询（{@code GET .../uplink-events}）与
 * Webhook 推送失败后的补偿排查使用。对齐设备通信中台详细设计 4.3.2、五 统一上行事件模型。
 */
@Data
@TableName("device_uplink_event_log")
public class DeviceUplinkEventLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("device_id")
    private String deviceId;

    @TableField("device_code")
    private String deviceCode;

    @TableField("device_type")
    private String deviceType;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("event_kind")
    private String eventKind;

    @TableField("transport")
    private String transport;

    /** 事件载荷（JSON 字符串） */
    @TableField("payload")
    private String payload;

    @TableField("reported_at")
    private LocalDateTime reportedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
