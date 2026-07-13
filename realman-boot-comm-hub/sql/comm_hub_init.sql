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

CREATE TABLE `comm_hub_topic_route` (
  `topic_suffix` varchar(64)  NOT NULL COMMENT '设备端向 Topic 后缀（device/{code}/ 之后的部分），如 status/report',
  `match_type`  varchar(16)  NOT NULL DEFAULT 'EXACT' COMMENT 'EXACT/PREFIX/ANT/REGEX',
  `priority`    int          NOT NULL DEFAULT 0 COMMENT '多规则命中时优先级，越大越优先',
  `handler_key` varchar(64)  DEFAULT NULL COMMENT '处理器键，默认等于 route_type',
  `route_type`   varchar(24)  NOT NULL COMMENT 'SSOT_ONLY / SSOT_AND_EVENT / EVENT_ONLY / TOKEN_REFRESH / BRIDGE_ACK / IGNORE',
  `event_kind`   varchar(32)  DEFAULT NULL COMMENT 'SSOT_AND_EVENT / EVENT_ONLY 必填，对应 EventKind 枚举名；其余 route_type 为空',
  `enabled`      tinyint(1)   NOT NULL DEFAULT 1,
  `description`  varchar(256) DEFAULT NULL,
  `updated_by`   varchar(64)  DEFAULT NULL,
  `updated_at`   datetime     DEFAULT NULL,
  PRIMARY KEY (`topic_suffix`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备端向 MQTT Topic 路由注册表（原 MqttMessageDispatcher 硬编码 switch，现落库可配置，见详细设计 2.4/已知限制第 6 项）';

-- 初始路由，对应此前硬编码 switch 的既有行为，逐条迁移，行为不变
INSERT INTO `comm_hub_topic_route` (`topic_suffix`, `match_type`, `priority`, `handler_key`, `route_type`, `event_kind`, `enabled`, `description`) VALUES
  ('status/report',     'EXACT', 100, 'SSOT_ONLY',      'SSOT_ONLY',      NULL,                1, '设备基座内部心跳/占用态同步，不对外发布上行事件'),
  ('ota/heartbeat',     'EXACT', 100, 'SSOT_AND_EVENT', 'SSOT_AND_EVENT', 'HEARTBEAT',         1, 'PRD 心跳接口，同步 SSOT 且对外发布 HEARTBEAT 事件'),
  ('ota/progress',      'EXACT', 100, 'EVENT_ONLY',     'EVENT_ONLY',     'OTA_PROGRESS',      1, 'OTA 升级进度上报，仅归一化为上行事件'),
  ('ota/status-report', 'EXACT', 100, 'EVENT_ONLY',     'EVENT_ONLY',     'OTA_STATUS_REPORT', 1, 'OTA 状态机上报，仅归一化为上行事件'),
  ('ota/token-refresh', 'EXACT', 100, 'TOKEN_REFRESH',  'TOKEN_REFRESH',  NULL,                1, 'Device Token 续签双向闭环，固定处理逻辑，不可通过 event_kind 配置'),
  ('bridge-ack',        'EXACT', 100, 'BRIDGE_ACK',     'BRIDGE_ACK',     NULL,                1, 'HTTP-MQTT 桥接下行指令的设备侧 ACK 回执，固定处理逻辑');

CREATE TABLE `device_uplink_event_log` (
  `id`           varchar(36)  NOT NULL COMMENT '雪花 ID 字符串，用作稳定增量消费游标',
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
  KEY `idx_tenant_event_kind` (`tenant_id`, `event_kind`),
  KEY `idx_event_kind_id` (`event_kind`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一上行事件落库记录（Webhook 推送来源 + 轮询兜底查询）';

CREATE TABLE `webhook_delivery_task` (
  `id`              varchar(36)  NOT NULL,
  `event_log_id`    varchar(36)  NOT NULL COMMENT 'device_uplink_event_log.id',
  `subscription_id` varchar(36)  NOT NULL COMMENT 'webhook_subscription.id',
  `tenant_id`       varchar(32)  NOT NULL,
  `callback_url`    varchar(512) NOT NULL,
  `hmac_secret`     varchar(128) NOT NULL,
  `request_body`    json         NOT NULL,
  `status`          varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/RETRYING/SUCCESS/DEAD',
  `attempt_count`   int          NOT NULL DEFAULT 0,
  `max_attempts`    int          NOT NULL DEFAULT 5,
  `next_retry_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error`      varchar(500) DEFAULT NULL,
  `last_status_code` int         DEFAULT NULL,
  `locked_by`       varchar(128) DEFAULT NULL,
  `locked_at`       datetime     DEFAULT NULL,
  `lock_expire_at`  datetime     DEFAULT NULL,
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_next_retry` (`status`, `next_retry_at`),
  KEY `idx_event_subscription` (`event_log_id`, `subscription_id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Webhook 持久化投递任务（可恢复重试/审计）';

CREATE TABLE `comm_hub_event_schema` (
  `id`              varchar(36) NOT NULL,
  `event_kind`      varchar(32) NOT NULL,
  `schema_version`  varchar(32) NOT NULL,
  `json_schema`     json        NOT NULL,
  `status`          varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DEPRECATED',
  `compatible_from` varchar(32) DEFAULT NULL,
  `created_at`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_schema_version` (`event_kind`, `schema_version`),
  KEY `idx_event_status` (`event_kind`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上行事件 Payload JSON Schema 台账';

CREATE TABLE `platform_capability` (
  `capability_code`   varchar(128) NOT NULL,
  `capability_type`   varchar(32)  NOT NULL COMMENT 'API/EVENT/WEBHOOK/MQTT_BRIDGE/INTERNAL_FEIGN',
  `provider_service`  varchar(64)  NOT NULL,
  `version`           varchar(32)  NOT NULL DEFAULT 'v1',
  `status`            varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DEPRECATED/OFFLINE',
  `auth_policy`       varchar(256) DEFAULT NULL,
  `rate_limit_policy` varchar(256) DEFAULT NULL,
  `sla_level`         varchar(32)  DEFAULT NULL,
  `owner`             varchar(64)  DEFAULT NULL,
  `created_at`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`capability_code`),
  KEY `idx_type_status` (`capability_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台能力目录';

CREATE TABLE `platform_capability_consumer` (
  `id`              varchar(36)  NOT NULL,
  `capability_code` varchar(128) NOT NULL,
  `consumer_type`   varchar(32)  NOT NULL COMMENT 'SERVICE/API_KEY/TENANT/THIRD_PARTY',
  `consumer_id`     varchar(128) NOT NULL,
  `tenant_id`       varchar(32)  DEFAULT NULL,
  `allowed_scope`   varchar(1024) DEFAULT NULL,
  `quota_policy`    varchar(256) DEFAULT NULL,
  `status`          varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_capability_status` (`capability_code`, `status`),
  KEY `idx_consumer` (`consumer_type`, `consumer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台能力消费方台账';
