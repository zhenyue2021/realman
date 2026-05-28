-- ============================================================
-- MQ 消息收发日志表
-- 记录 RocketMQ 生产者发送和消费者接收的每条消息
-- 回滚：DROP TABLE IF EXISTS `iot_mq_message_log`;
-- ============================================================
CREATE TABLE `iot_mq_message_log` (
  `id`             varchar(36)   NOT NULL                 COMMENT '主键（雪花ID）',
  `direction`      tinyint       NOT NULL                 COMMENT '消息方向：1=发送，2=接收',
  `topic`          varchar(128)  NOT NULL                 COMMENT 'MQ Topic',
  `tag`            varchar(128)  DEFAULT NULL             COMMENT 'MQ Tag',
  `consumer_group` varchar(128)  DEFAULT NULL             COMMENT '消费者组（发送时为 NULL）',
  `message_id`     varchar(128)  DEFAULT NULL             COMMENT 'MQ 消息 ID',
  `message_body`   text          DEFAULT NULL             COMMENT '消息体 JSON（超 4000 字符自动截断）',
  `caller_class`   varchar(256)  DEFAULT NULL             COMMENT '发送方/消费方简类名',
  `status`         tinyint       NOT NULL DEFAULT 1       COMMENT '结果状态：1=成功，2=失败',
  `fail_reason`    text          DEFAULT NULL             COMMENT '失败原因（超 500 字符截断）',
  `cost_time`      bigint        DEFAULT NULL             COMMENT '耗时（毫秒）',
  `trace_id`       varchar(64)   DEFAULT NULL             COMMENT '链路追踪 ID',
  `create_time`    datetime      NOT NULL                 COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_topic`       (`topic`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_trace_id`    (`trace_id`),
  KEY `idx_message_id`  (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 消息收发日志';
