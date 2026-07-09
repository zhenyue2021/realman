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
 * 第三方业务平台的上行事件 Webhook 订阅。对齐设备通信中台详细设计 4.3.2：
 * 第三方不便长连接轮询时，由通信中台按 HMAC 签名主动推送 {@code DeviceUplinkEvent}。
 */
@Data
@TableName("webhook_subscription")
public class WebhookSubscription implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("callback_url")
    private String callbackUrl;

    /**
     * HMAC-SHA256 签名密钥。与设备侧凭证不同，这里通信中台是"签名方"而非"校验方"，
     * 每次推送都需要用明文密钥现算 HMAC，因此不能只存哈希——这与 deviceSecret 的
     * 单向哈希校验模型不是同一种场景。已知限制：本轮按明文落库（创建时随机生成，
     * 一次性经 HTTPS 返回给订阅方），生产环境应改为应用层加密存储（如 KMS/Jasypt），
     * 本轮暂不引入额外加解密基础设施。
     */
    @TableField("hmac_secret")
    private String hmacSecret;

    /** 逗号分隔的 EventKind 名称，空表示订阅全部事件种类 */
    @TableField("event_kinds")
    private String eventKinds;

    /** ACTIVE / DISABLED */
    @TableField("status")
    private String status;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
