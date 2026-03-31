package org.jeecg.modules.integration.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 外部系统参数接收记录
 */
@Data
@TableName("integration_external_param_record")
public class ExternalParamRecord implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /** 外部系统编码（如 DEW） */
    @TableField("source_system")
    private String sourceSystem;

    /** 目标系统编码（本系统内部标识） */
    @TableField("target_system")
    private String targetSystem;

    /** 请求唯一标识，用于幂等校验 */
    @TableField("request_id")
    private String requestId;

    /** 业务类型/场景码（如 request_url） */
    @TableField("biz_type")
    private String bizType;

    /** params.timestamp */
    @TableField("param_timestamp")
    private String paramTimestamp;

    /** STS endpoint */
    @TableField("endpoint")
    private String endpoint;

    /** OSS Bucket 名称 */
    @TableField("bucket")
    private String bucket;

    /** 凭证北京时间过期时间 */
    @TableField("bj_expiration")
    private String bjExpiration;

    /** 凭证 UTC 过期时间（ISO-8601，用于计算缓存 TTL） */
    @TableField("utc_expiration")
    private String utcExpiration;

    /** STS AccessKeyId */
    @TableField("access_key_id")
    private String accessKeyId;

    /** STS AccessKeySecret */
    @TableField("access_key_secret")
    private String accessKeySecret;

    /** STS SecurityToken（长文本） */
    @TableField("security_token")
    private String securityToken;

    /** 原始 params JSON（兜底存储，便于将来扩展字段） */
    @TableField("raw_params")
    private String rawParams;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
