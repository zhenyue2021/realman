-- Store OTA uplink polling progress by stable event log ID instead of reportedAt.
ALTER TABLE `ota_uplink_poll_cursor`
  MODIFY COLUMN `cursor_at` datetime DEFAULT NULL COMMENT '历史 reportedAt 游标，仅兼容旧数据，不再作为消费位点',
  ADD COLUMN `cursor_id` varchar(36) DEFAULT NULL COMMENT '最后成功扫描的上行事件日志稳定 ID 游标' AFTER `cursor_at`;
