-- comm_hub_topic_route 路由匹配规则升级脚本
-- 适用于已部署过旧版 comm_hub_init.sql 的环境；新环境可直接使用 comm_hub_init.sql。

ALTER TABLE `comm_hub_topic_route`
  MODIFY COLUMN `topic_suffix` varchar(256) NOT NULL COMMENT '设备端向 Topic 后缀（device/{code}/ 之后的部分）；按 match_type 可为精确值、前缀、Ant pattern 或正则',
  ADD COLUMN `match_type` varchar(16) NOT NULL DEFAULT 'EXACT' COMMENT 'EXACT / PREFIX / ANT_PATTERN / REGEX；历史精确路由默认 EXACT' AFTER `topic_suffix`,
  ADD COLUMN `priority` int NOT NULL DEFAULT 0 COMMENT '匹配优先级，数值越大越先匹配；同优先级再按匹配类型和模式长度兜底排序' AFTER `match_type`;

UPDATE `comm_hub_topic_route`
SET `match_type` = 'EXACT', `priority` = 0
WHERE `match_type` IS NULL OR `match_type` = '';
