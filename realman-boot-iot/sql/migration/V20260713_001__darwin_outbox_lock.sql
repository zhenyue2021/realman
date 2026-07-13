-- 增量迁移：Darwin HTTP Outbox 锁租约
ALTER TABLE `darwin_http_outbox`
  ADD COLUMN `locked_by` varchar(128) DEFAULT NULL AFTER `last_error`,
  ADD COLUMN `locked_at` datetime DEFAULT NULL AFTER `locked_by`,
  ADD COLUMN `lock_expire_at` datetime DEFAULT NULL AFTER `locked_at`;
