-- 设备管理业务平台建表脚本
-- 对齐 docs/design/2026-07-08-device-foundation-detailed-design.md 第三章 3.2 数据模型
-- 本轮只建 DeviceMgmtFeignClient 契约需要的两张表（凭证 + 注册一次性凭证）；
-- 租户授权、绑定、审计等表留给后续对外 REST 补充时一并建。

CREATE TABLE `device_credential` (
  `device_id`              varchar(36)  NOT NULL COMMENT '关联 device_info.device_id',
  `device_secret_hash`     varchar(128) DEFAULT NULL COMMENT 'MQTT 连接层密码（SHA-256 哈希存储）',
  `device_secret_version`  int          NOT NULL DEFAULT 1 COMMENT '密钥版本号，重置时递增',
  `token_jti`              varchar(64)  DEFAULT NULL COMMENT '当前有效 Device Token 的 JWT ID',
  `token_issued_at`        datetime     DEFAULT NULL,
  `token_expires_at`       datetime     DEFAULT NULL,
  `token_revoked_at`       datetime     DEFAULT NULL COMMENT '非空即视为已吊销',
  `token_revoke_reason`    varchar(128) DEFAULT NULL,
  PRIMARY KEY (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备双凭证体系（连接层密钥 + 业务身份 Token）';

CREATE TABLE `device_registration_secret` (
  `id`           varchar(36)  NOT NULL,
  `device_code`  varchar(64)  NOT NULL COMMENT '目标设备序列号',
  `tenant_id`    varchar(32)  NOT NULL COMMENT '所属租户',
  `secret_hash`  varchar(128) NOT NULL COMMENT '一次性注册凭证哈希存储，不存明文',
  `status`       varchar(16)  NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED / USED / EXPIRED',
  `expires_at`   datetime     NOT NULL COMMENT '生成时刻起 registration_secret_expiry_days（默认 365 天）',
  `used_at`      datetime     DEFAULT NULL,
  `created_by`   varchar(64)  DEFAULT NULL COMMENT '生成操作人（超管）',
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_code_status` (`device_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备一次性注册凭证';
