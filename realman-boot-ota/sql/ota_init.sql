-- OTA 平台建表脚本
-- 对齐 docs/design/2026-07-09-ota-platform-detailed-design.md（已对照
-- 《达尔文设备升级平台 PRD V1.0.0》原文核对）

CREATE TABLE `ota_firmware` (
  `package_id`             varchar(36)  NOT NULL,
  `firmware_file_name`     varchar(256) NOT NULL,
  `device_type`            varchar(16)  NOT NULL COMMENT 'master / slave',
  `version`                varchar(32)  NOT NULL COMMENT '统一大写 V 格式',
  `min_version`            varchar(32)  DEFAULT NULL,
  `compatible_models`      json         DEFAULT NULL COMMENT '为空数组视为全型号',
  `install_command`        varchar(512) DEFAULT NULL,
  `rollback_command`       varchar(512) DEFAULT NULL,
  `healthcheck_command`    varchar(512) DEFAULT NULL,
  `risk_level`             varchar(16)  NOT NULL DEFAULT 'normal' COMMENT 'normal / high_risk',
  `cancelable_in_executing` tinyint(1)  NOT NULL DEFAULT 0,
  `sha256`                 varchar(64)  NOT NULL,
  `sig_oss_path`           varchar(512) DEFAULT NULL,
  `sig_local_path`         varchar(512) DEFAULT NULL,
  `key_id`                 varchar(36)  NOT NULL COMMENT '关联 ota_key，上传时自动关联当前 active 公钥',
  `storage_source`         varchar(16)  NOT NULL COMMENT 'LOCAL / OSS',
  `download_url`           varchar(1024) DEFAULT NULL,
  `download_url_expires_at` datetime    DEFAULT NULL COMMENT 'OSS 预签名 URL 到期时间；LOCAL 存储恒为空',
  `file_size_mb`           int          NOT NULL,
  `created_by`             varchar(64)  DEFAULT NULL,
  `created_at`             datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`package_id`),
  KEY `idx_device_type_version` (`device_type`, `version`),
  KEY `idx_key_id` (`key_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='固件包';

CREATE TABLE `ota_key` (
  `key_id`           varchar(36)  NOT NULL,
  `algorithm`        varchar(16)  NOT NULL DEFAULT 'Ed25519',
  `public_key_pem`   varchar(1024) NOT NULL,
  `key_fingerprint`  varchar(64)  NOT NULL COMMENT 'SHA-256 指纹前 32 位十六进制',
  `key_alias`        varchar(64)  DEFAULT NULL,
  `status`           varchar(24)  NOT NULL DEFAULT 'pending_activation' COMMENT 'active / pending_activation / revoked',
  `activated_at`     datetime     DEFAULT NULL,
  `revoked_at`       datetime     DEFAULT NULL,
  `revoke_reason`    varchar(128) DEFAULT NULL,
  `created_by`       varchar(64)  DEFAULT NULL,
  `created_at`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`key_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OTA 签名公钥生命周期';

CREATE TABLE `ota_task` (
  `task_id`                        varchar(36)  NOT NULL,
  `device_type`                    varchar(16)  NOT NULL,
  `package_id`                     varchar(36)  NOT NULL,
  `upgrade_mode`                   varchar(24)  NOT NULL COMMENT 'BY_SN / BY_MODEL / ALL / BY_TENANT_MODEL',
  `target_selector`                json         NOT NULL,
  `tenant_id`                      varchar(32)  DEFAULT NULL,
  `bandwidth_limit_mbps`           decimal(10,2) DEFAULT NULL,
  `fail_threshold_type`            varchar(16)  NOT NULL DEFAULT 'count',
  `fail_threshold`                 int          NOT NULL DEFAULT 5,
  `on_threshold_exceeded`          varchar(16)  NOT NULL DEFAULT 'pause',
  `status`                         varchar(24)  NOT NULL DEFAULT 'IN_PROGRESS',
  `stop_all_triggered`             tinyint(1)   NOT NULL DEFAULT 0,
  `active_fail_threshold_snapshot` int          DEFAULT NULL,
  `threshold_triggered_at`         datetime     DEFAULT NULL,
  `paused_at`                      datetime     DEFAULT NULL,
  `resume_count`                   int          NOT NULL DEFAULT 0,
  `created_by`                     varchar(64)  DEFAULT NULL,
  `created_at`                     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`                     datetime     DEFAULT NULL,
  PRIMARY KEY (`task_id`),
  KEY `idx_status_created_at` (`status`, `created_at`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='升级任务（批量聚合视图）';

CREATE TABLE `ota_task_device` (
  `id`                 varchar(36)  NOT NULL,
  `task_id`            varchar(36)  NOT NULL,
  `device_id`          varchar(36)  NOT NULL,
  `device_code`        varchar(64)  NOT NULL,
  `state`              varchar(24)  NOT NULL COMMENT '15 态之一，见 OtaTaskState',
  `progress_pct`       int          NOT NULL DEFAULT 0,
  `sub_stage`          varchar(24)  DEFAULT NULL COMMENT 'install_exec / symlink_switch / os_sync',
  `sig_verify_result`  varchar(16)  DEFAULT NULL COMMENT 'pass / fail / skipped',
  `upgrade_error_code` varchar(48)  DEFAULT NULL,
  `upgrade_error_msg`  varchar(256) DEFAULT NULL,
  `rollback_reason`    varchar(256) DEFAULT NULL,
  `reported_at`        datetime     DEFAULT NULL,
  `state_changed_at`   datetime     DEFAULT NULL,
  `retry_count`        int          NOT NULL DEFAULT 0,
  `cancel_requested_at` datetime    DEFAULT NULL COMMENT 'EXECUTING 阶段发起取消请求的时间，非空表示等待 symlink_switched 上报',
  `dispatch_attempt_count` int      NOT NULL DEFAULT 0 COMMENT '下行发布尝试次数（区别于 retry_count 的运维手动重试），达到 dispatch_max_attempts 后置 FAILED',
  `last_dispatch_attempt_at` datetime DEFAULT NULL COMMENT '最近一次下行发布尝试时间，供自动重试扫描判断退避间隔',
  `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_device_id_state` (`device_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备级升级子任务（15 态状态机载体）';

CREATE TABLE `ota_audit_log` (
  `id`                  varchar(36)  NOT NULL,
  `operation_type`      varchar(32)  NOT NULL,
  `operator`            varchar(64)  NOT NULL,
  `operator_tenant_id`  varchar(32)  DEFAULT NULL,
  `target_tenant_id`    varchar(32)  DEFAULT NULL,
  `audit_level`         varchar(16)  NOT NULL DEFAULT 'normal',
  `task_id`             varchar(36)  DEFAULT NULL,
  `package_id`          varchar(36)  DEFAULT NULL,
  `key_id`              varchar(36)  DEFAULT NULL,
  `detail`              json         DEFAULT NULL,
  `created_at`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_operation_type_created_at` (`operation_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OTA 操作审计日志';

CREATE TABLE `ota_system_setting` (
  `setting_key`   varchar(64)  NOT NULL,
  `setting_value` varchar(256) NOT NULL,
  `updated_by`    varchar(64)  DEFAULT NULL,
  `updated_at`    datetime     DEFAULT NULL,
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统设置（17 项，见 OtaSystemSettingDefaults）';

CREATE TABLE `ota_uplink_poll_cursor` (
  `event_kind` varchar(32) NOT NULL COMMENT 'OTA_PROGRESS / OTA_STATUS_REPORT，各自独立游标',
  `cursor_at`  datetime    DEFAULT NULL COMMENT '历史 reportedAt 游标，仅兼容旧数据，不再作为消费位点',
  `cursor_id`  varchar(36) DEFAULT NULL COMMENT '最后成功扫描的上行事件日志稳定 ID 游标',
  `updated_at` datetime    DEFAULT NULL,
  PRIMARY KEY (`event_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上行事件轮询游标持久化，按 eventKind 各自维护，修复此前内存游标不持久/多实例不安全的已知限制';

-- 17 项系统设置初始默认值（对齐详细设计第十章 / PRD 9.9）
INSERT INTO `ota_system_setting` (`setting_key`, `setting_value`) VALUES
  ('disk_valid_seconds', '300'),
  ('memory_valid_seconds', '300'),
  ('power_valid_seconds', '300'),
  ('network_valid_seconds', '300'),
  ('pending_url_check_interval_minutes', '15'),
  ('oss_url_expiry_seconds', '86400'),
  ('poll_interval_seconds', '30'),
  ('push_exempt_seconds', '30'),
  ('device_offline_timeout_hours', '72'),
  ('pending_online_device_timeout_minutes', '30'),
  ('cancel_ack_timeout_seconds', '60'),
  ('device_token_expiry_days', '365'),
  ('registration_secret_expiry_days', '365'),
  ('default_fail_threshold_type', 'count'),
  ('default_fail_threshold', '5'),
  ('default_on_threshold_exceeded', 'pause'),
  ('heartbeat_interval_seconds', '60'),
  ('max_firmware_size_mb', '2048'),
  ('max_batch_devices', '1000'),
  ('global_sig_verify_enabled', 'true'),
  ('dispatch_max_attempts', '3'),
  ('dispatch_retry_interval_seconds', '60'),
  ('version_lag_warn_minor_diff', '2'),
  ('version_lag_critical_minor_diff', '5');
