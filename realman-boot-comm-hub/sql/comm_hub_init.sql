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


CREATE TABLE `webhook_delivery_task` (
  `id`              varchar(36)  NOT NULL,
  `event_log_id`    varchar(36)  NOT NULL COMMENT '关联 device_uplink_event_log.id',
  `subscription_id` varchar(36)  NOT NULL COMMENT '关联 webhook_subscription.id',
  `callback_url`    varchar(512) NOT NULL COMMENT '任务创建时订阅回调地址快照',
  `status`          varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENDING / RETRYING / SUCCESS / FAILED',
  `attempt_count`   int          NOT NULL DEFAULT 0 COMMENT '已执行 HTTP 推送次数',
  `next_retry_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次可扫描投递时间',
  `last_error`      varchar(1024) DEFAULT NULL COMMENT '最近一次失败原因',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_next_retry` (`status`, `next_retry_at`),
  KEY `idx_event_log_id` (`event_log_id`),
  KEY `idx_subscription_id` (`subscription_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Webhook 异步投递任务（定时扫描 + 持久化退避重试）';

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
  `topic_suffix` varchar(256) NOT NULL COMMENT '设备端向 Topic 后缀（device/{code}/ 之后的部分），如 status/report；按 match_type 可为精确值、前缀、Ant pattern 或正则',
  `match_type`   varchar(16)  NOT NULL DEFAULT 'EXACT' COMMENT 'EXACT / PREFIX / ANT_PATTERN / REGEX；历史精确路由默认 EXACT',
  `priority`     int          NOT NULL DEFAULT 0 COMMENT '匹配优先级，数值越大越先匹配；同优先级再按匹配类型和模式长度兜底排序',
  `route_type`   varchar(24)  NOT NULL COMMENT 'SSOT_ONLY / SSOT_AND_EVENT / EVENT_ONLY / TOKEN_REFRESH / BRIDGE_ACK / IGNORE',
  `event_kind`   varchar(32)  DEFAULT NULL COMMENT 'SSOT_AND_EVENT / EVENT_ONLY 必填，对应 EventKind 枚举名；其余 route_type 为空',
  `enabled`      tinyint(1)   NOT NULL DEFAULT 1,
  `description`  varchar(256) DEFAULT NULL,
  `updated_by`   varchar(64)  DEFAULT NULL,
  `updated_at`   datetime     DEFAULT NULL,
  PRIMARY KEY (`topic_suffix`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备端向 MQTT Topic 路由注册表（原 MqttMessageDispatcher 硬编码 switch，现落库可配置，见详细设计 2.4/已知限制第 6 项）';

-- 初始路由，对应此前硬编码 switch 的既有行为，逐条迁移，行为不变
INSERT INTO `comm_hub_topic_route` (`topic_suffix`, `match_type`, `priority`, `route_type`, `event_kind`, `enabled`, `description`) VALUES
  ('status/report',     'EXACT', 0, 'SSOT_ONLY',      NULL,                1, '设备基座内部心跳/占用态同步，不对外发布上行事件'),
  ('ota/heartbeat',     'EXACT', 0, 'SSOT_AND_EVENT', 'HEARTBEAT',         1, 'PRD 心跳接口，同步 SSOT 且对外发布 HEARTBEAT 事件'),
  ('ota/progress',      'EXACT', 0, 'EVENT_ONLY',     'OTA_PROGRESS',      1, 'OTA 升级进度上报，仅归一化为上行事件'),
  ('ota/status-report', 'EXACT', 0, 'EVENT_ONLY',     'OTA_STATUS_REPORT', 1, 'OTA 状态机上报，仅归一化为上行事件'),
  ('ota/token-refresh', 'EXACT', 0, 'TOKEN_REFRESH',  NULL,                1, 'Device Token 续签双向闭环，固定处理逻辑，不可通过 event_kind 配置'),
  ('bridge-ack',        'EXACT', 0, 'BRIDGE_ACK',     NULL,                1, 'HTTP-MQTT 桥接下行指令的设备侧 ACK 回执，固定处理逻辑');

CREATE TABLE `device_uplink_event_log` (
  `id`           varchar(36)  NOT NULL COMMENT '稳定递增雪花 ID 字符串，作为轮询消费游标',
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
  KEY `idx_event_kind_id` (`event_kind`, `id`),
  KEY `idx_tenant_event_kind` (`tenant_id`, `event_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一上行事件落库记录（Webhook 推送来源 + 轮询兜底查询）';
