-- 设备通信中台建表脚本
-- 对齐 docs/design/2026-07-08-device-comm-hub-detailed-design.md 4.3.2、五 统一上行事件模型

CREATE TABLE `webhook_subscription` (
  `id`            varchar(36)  NOT NULL,
  `tenant_id`     varchar(32)  NOT NULL,
  `callback_url`  varchar(512) NOT NULL,
  `hmac_secret`   varchar(128) NOT NULL COMMENT '签名密钥，明文存储（签名方需要现算 HMAC，非单向哈希场景），生产环境建议应用层加密',
  `event_kinds`   varchar(256) DEFAULT NULL COMMENT '逗号分隔的 EventKind 名称，空表示订阅全部',
  `device_id_filter` varchar(1024) DEFAULT NULL COMMENT '逗号分隔的 deviceId 列表，空表示不按设备过滤（订阅该租户全部设备）',
  `consecutive_failure_count` int NOT NULL DEFAULT 0 COMMENT '连续投递失败次数，达到阈值自动置为 PAUSED',
  `status`        varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED（连续失败自动暂停）/ DISABLED（手动停用）',
  `created_by`    varchar(64)  DEFAULT NULL,
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上行事件 Webhook 订阅';

CREATE TABLE `comm_hub_api_key` (
  `id`                  varchar(36)  NOT NULL,
  `tenant_id`           varchar(32)  NOT NULL,
  `api_key_hash`        varchar(64)  NOT NULL COMMENT 'SHA-256(原始 Key)，原始 Key 只在创建时经 HTTPS 返回一次，不落库明文',
  `key_prefix`          varchar(16)  NOT NULL COMMENT '原始 Key 前 8 位，仅供台账/审计辨识，不可用于鉴权',
  `device_scope`        varchar(2048) NOT NULL DEFAULT '*' COMMENT '逗号分隔 deviceId 列表，* 表示不限设备（仍受 tenant_id 约束）',
  `topic_suffix_scope`  varchar(1024) NOT NULL DEFAULT '*' COMMENT '逗号分隔 topicSuffix（支持 xxx/* 前缀通配），* 表示不限 Topic',
  `status`              varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED',
  `created_by`          varchar(64)  DEFAULT NULL,
  `created_at`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_key_hash` (`api_key_hash`),
  KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HTTP-MQTT 桥接第三方系统身份，见设备通信中台详细设计 4.3.1/4.5';

CREATE TABLE `device_uplink_event_log` (
  `id`           varchar(36)  NOT NULL,
  `device_id`    varchar(36)  DEFAULT NULL,
  `device_code`  varchar(64)  DEFAULT NULL,
  `device_type`  varchar(20)  DEFAULT NULL,
  `tenant_id`    varchar(32)  DEFAULT NULL,
  `event_kind`   varchar(32)  DEFAULT NULL COMMENT 'HEARTBEAT/OTA_PROGRESS/OTA_STATUS_REPORT/ONLINE/OFFLINE/REGISTER/TOKEN_REFRESH',
  `transport`    varchar(16)  DEFAULT NULL COMMENT 'MQTT / HTTP',
  `payload`      json         DEFAULT NULL,
  `reported_at`  datetime     DEFAULT NULL,
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_reported_at` (`device_id`, `reported_at`),
  KEY `idx_tenant_event_kind` (`tenant_id`, `event_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一上行事件落库记录（Webhook 推送来源 + 轮询兜底查询）';
