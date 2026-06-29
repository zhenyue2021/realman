-- ============================================================
-- IoT 日志归档历史表（主表超期数据迁移目标）
-- 适用：已部署环境手工执行；新环境可合并进 iot_init.sql
-- 配套任务：iotLogArchiveJob（XXL-Job）
-- ============================================================

CREATE TABLE IF NOT EXISTS `iot_device_command_record_history` (
    `id` varchar(32) NOT NULL COMMENT '主键（与主表一致）',
    `command_id` varchar(32) NOT NULL COMMENT '指令唯一ID',
    `device_id` varchar(32) DEFAULT NULL COMMENT '设备ID',
    `device_code` varchar(64) NOT NULL COMMENT '设备编号',
    `command_type` varchar(32) NOT NULL COMMENT '指令类型',
    `device_type` varchar(16) NOT NULL DEFAULT 'device' COMMENT 'device/master',
    `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAIL/TIMEOUT',
    `fail_reason` varchar(256) DEFAULT NULL COMMENT '失败原因',
    `operator` varchar(64) DEFAULT NULL COMMENT '操作人',
    `params_json` text COMMENT '下发指令 JSON',
    `ack_data_json` text COMMENT 'ACK JSON',
    `send_time` datetime NOT NULL COMMENT '指令下发时间',
    `ack_time` datetime DEFAULT NULL COMMENT 'ACK 时间',
    `create_time` datetime NOT NULL COMMENT '记录创建时间',
    `update_time` datetime DEFAULT NULL COMMENT '记录更新时间',
    `backup_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_hist_cmd_device_code` (`device_code`),
    KEY `idx_hist_cmd_create_time` (`create_time`),
    KEY `idx_hist_cmd_backup_time` (`backup_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备指令记录归档表';

CREATE TABLE IF NOT EXISTS `iot_device_operation_log_history` (
    `id` varchar(32) NOT NULL COMMENT '主键（与主表一致）',
    `device_id` varchar(32) DEFAULT NULL COMMENT '设备ID',
    `device_code` varchar(64) NOT NULL COMMENT '设备编号',
    `operation_type` varchar(32) NOT NULL COMMENT '操作类型',
    `operation_desc` varchar(512) NOT NULL COMMENT '操作描述',
    `operation_detail` text COMMENT '操作详情 JSON',
    `operation_source` varchar(16) DEFAULT 'PLATFORM' COMMENT 'PLATFORM/DEVICE',
    `operation_result` varchar(16) DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAIL/PENDING',
    `fail_reason` varchar(256) DEFAULT NULL COMMENT '失败原因',
    `operator` varchar(64) DEFAULT NULL COMMENT '操作人',
    `client_ip` varchar(64) DEFAULT NULL COMMENT '客户端 IP',
    `create_time` datetime NOT NULL COMMENT '记录创建时间',
    `operation_time` datetime NOT NULL COMMENT '操作发生时间',
    `backup_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_hist_op_device_code` (`device_code`),
    KEY `idx_hist_op_operation_time` (`operation_time`),
    KEY `idx_hist_op_backup_time` (`backup_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备操作日志归档表';

CREATE TABLE IF NOT EXISTS `iot_mq_message_log_history` (
    `id` varchar(36) NOT NULL COMMENT '主键（与主表一致）',
    `direction` tinyint NOT NULL COMMENT '1=发送，2=接收',
    `topic` varchar(128) NOT NULL COMMENT 'MQ Topic',
    `tag` varchar(128) DEFAULT NULL COMMENT 'MQ Tag',
    `consumer_group` varchar(128) DEFAULT NULL COMMENT '消费者组',
    `message_id` varchar(128) DEFAULT NULL COMMENT 'MQ 消息 ID',
    `message_body` mediumtext COMMENT '消息体 JSON',
    `caller_class` varchar(256) DEFAULT NULL COMMENT '调用类简名',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=成功，2=失败',
    `fail_reason` text COMMENT '失败原因',
    `cost_time` bigint DEFAULT NULL COMMENT '耗时（毫秒）',
    `trace_id` varchar(64) DEFAULT NULL COMMENT '链路追踪 ID',
    `create_time` datetime NOT NULL COMMENT '记录时间',
    `backup_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_hist_mq_topic` (`topic`),
    KEY `idx_hist_mq_create_time` (`create_time`),
    KEY `idx_hist_mq_backup_time` (`backup_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MQ 消息日志归档表';

-- 主表归档扫描索引（若已存在则跳过）
SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @db AND table_name = 'iot_device_command_record' AND index_name = 'idx_create_time') = 0,
    'ALTER TABLE `iot_device_command_record` ADD KEY `idx_create_time` (`create_time`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
