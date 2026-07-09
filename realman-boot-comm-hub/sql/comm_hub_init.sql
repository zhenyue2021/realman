-- 设备通信中台建表脚本
-- 对齐 docs/design/2026-07-08-device-comm-hub-detailed-design.md 4.3.2、五 统一上行事件模型

CREATE TABLE `webhook_subscription` (
  `id`            varchar(36)  NOT NULL,
  `tenant_id`     varchar(32)  NOT NULL,
  `callback_url`  varchar(512) NOT NULL,
  `hmac_secret`   varchar(128) NOT NULL COMMENT '签名密钥，明文存储（签名方需要现算 HMAC，非单向哈希场景），生产环境建议应用层加密',
  `event_kinds`   varchar(256) DEFAULT NULL COMMENT '逗号分隔的 EventKind 名称，空表示订阅全部',
  `status`        varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `created_by`    varchar(64)  DEFAULT NULL,
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上行事件 Webhook 订阅';

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
