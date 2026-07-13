-- 增量迁移：Webhook 投递锁租约、Topic 模式路由、事件 Schema 与能力目录
ALTER TABLE `webhook_delivery_task`
  ADD COLUMN `locked_by` varchar(128) DEFAULT NULL AFTER `last_status_code`,
  ADD COLUMN `locked_at` datetime DEFAULT NULL AFTER `locked_by`,
  ADD COLUMN `lock_expire_at` datetime DEFAULT NULL AFTER `locked_at`;

ALTER TABLE `comm_hub_topic_route`
  ADD COLUMN `match_type` varchar(16) NOT NULL DEFAULT 'EXACT' AFTER `topic_suffix`,
  ADD COLUMN `priority` int NOT NULL DEFAULT 0 AFTER `match_type`,
  ADD COLUMN `handler_key` varchar(64) DEFAULT NULL AFTER `priority`;

CREATE TABLE IF NOT EXISTS `comm_hub_event_schema` (
  `id` varchar(36) NOT NULL,
  `event_kind` varchar(32) NOT NULL,
  `schema_version` varchar(32) NOT NULL,
  `json_schema` json NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `compatible_from` varchar(32) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_schema_version` (`event_kind`, `schema_version`),
  KEY `idx_event_status` (`event_kind`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `platform_capability` (
  `capability_code` varchar(128) NOT NULL,
  `capability_type` varchar(32) NOT NULL,
  `provider_service` varchar(64) NOT NULL,
  `version` varchar(32) NOT NULL DEFAULT 'v1',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `auth_policy` varchar(256) DEFAULT NULL,
  `rate_limit_policy` varchar(256) DEFAULT NULL,
  `sla_level` varchar(32) DEFAULT NULL,
  `owner` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`capability_code`),
  KEY `idx_type_status` (`capability_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `platform_capability_consumer` (
  `id` varchar(36) NOT NULL,
  `capability_code` varchar(128) NOT NULL,
  `consumer_type` varchar(32) NOT NULL,
  `consumer_id` varchar(128) NOT NULL,
  `tenant_id` varchar(32) DEFAULT NULL,
  `allowed_scope` varchar(1024) DEFAULT NULL,
  `quota_policy` varchar(256) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_capability_status` (`capability_code`, `status`),
  KEY `idx_consumer` (`consumer_type`, `consumer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
