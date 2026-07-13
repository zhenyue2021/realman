-- Introduce a stable, monotonically increasing consumer cursor for uplink event polling.
-- New rows are generated with Snowflake ID strings by UplinkEventServiceImpl.
ALTER TABLE `device_uplink_event_log`
  MODIFY COLUMN `id` varchar(36) NOT NULL COMMENT '稳定递增雪花 ID 字符串，作为轮询消费游标',
  ADD KEY `idx_event_kind_id` (`event_kind`, `id`);
