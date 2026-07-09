-- 设备管理业务平台建表脚本
-- 对齐 docs/design/2026-07-08-device-foundation-detailed-design.md 第三章 3.2 数据模型

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

CREATE TABLE `device_tenant_auth` (
  `id`           varchar(36)  NOT NULL,
  `device_id`    varchar(36)  NOT NULL COMMENT '关联 device_info.device_id',
  `tenant_id`    varchar(32)  NOT NULL,
  `granted_by`   varchar(64)  DEFAULT NULL COMMENT '操作人',
  `granted_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `valid_until`  datetime     DEFAULT NULL COMMENT '有效期，NULL 表示长期有效',
  PRIMARY KEY (`id`),
  KEY `idx_device_id` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备-租户授权变更历史';

CREATE TABLE `device_binding` (
  `id`                varchar(36)  NOT NULL,
  `master_device_id`  varchar(36)  NOT NULL COMMENT '主控端 device_id',
  `slave_device_id`   varchar(36)  NOT NULL COMMENT '机器人 device_id',
  `tenant_id`         varchar(32)  NOT NULL,
  `bind_mode`         varchar(16)  NOT NULL DEFAULT 'V1_ONE_TO_ONE' COMMENT 'V1_ONE_TO_ONE / V2_MANY_TO_MANY',
  `status`            varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED',
  `created_by`        varchar(64)  DEFAULT NULL,
  `created_at`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_master_device` (`master_device_id`, `status`),
  KEY `idx_slave_device` (`slave_device_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主控端 ↔ 机器人授权绑定';

CREATE TABLE `device_operation_audit_log` (
  `id`                  varchar(36)  NOT NULL,
  `device_id`           varchar(36)  DEFAULT NULL COMMENT '部分操作（如凭证生成）可能未绑定具体设备',
  `operation_type`      varchar(32)  NOT NULL COMMENT 'REGISTER/TOKEN_ISSUE/TOKEN_REVOKE/SECRET_RESET/TEST_FLAG/TENANT_AUTH/BINDING/LIFECYCLE_CHANGE/...',
  `operator`            varchar(64)  NOT NULL,
  `operator_tenant_id`  varchar(32)  DEFAULT NULL,
  `target_tenant_id`    varchar(32)  DEFAULT NULL,
  `audit_level`         varchar(16)  NOT NULL DEFAULT 'normal' COMMENT 'normal / high / critical',
  `detail`              json         DEFAULT NULL COMMENT '操作详情快照',
  `created_at`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_id` (`device_id`),
  KEY `idx_operation_type_created_at` (`operation_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备管理操作审计日志';
