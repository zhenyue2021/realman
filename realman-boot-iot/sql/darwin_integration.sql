-- ============================================================
-- 达尔文数采平台 RocketMQ 集成 DB 变更
-- 日期：2026-04-27
-- ============================================================

-- ① work_order 表新增 source 字段（DEFAULT 1 = 内部创建，存量数据自动兼容）
ALTER TABLE work_order
    ADD COLUMN `source` TINYINT NOT NULL DEFAULT 1 COMMENT '来源 1=内部创建 2=达尔文平台' AFTER `tenant_id`,
    ADD INDEX `idx_source` (`source`);

-- ② 新建达尔文工单映射表
CREATE TABLE IF NOT EXISTS `darwin_workorder_mapping`
(
    `id`               VARCHAR(64)  NOT NULL COMMENT '主键（雪花算法）',
    `work_order_id`    VARCHAR(64)  NOT NULL COMMENT '内部工单ID',
    `darwin_order_id`  VARCHAR(64)  NOT NULL COMMENT '达尔文平台工单ID',
    `darwin_agent_id`  VARCHAR(64)  NULL     COMMENT '达尔文操作员ID',
    `darwin_agent_name` VARCHAR(64) NULL     COMMENT '达尔文操作员姓名',
    `darwin_dept_id`   VARCHAR(64)  NULL     COMMENT '达尔文部门ID',
    `darwin_dept_name` VARCHAR(64)  NULL     COMMENT '达尔文部门名称',
    `raw_message`      TEXT         NULL     COMMENT '原始消息体（排查用）',
    `del_flag`         TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标志 0=正常 1=删除',
    `create_by`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人',
    `create_time`      DATETIME     NOT NULL COMMENT '创建时间',
    `update_by`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新人',
    `update_time`      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_darwin_order_id` (`darwin_order_id`),
    INDEX `idx_work_order_id` (`work_order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '达尔文平台工单映射表';

-- ③ work_order 表 agent_id / compliance_id 改为允许 NULL
--    达尔文创单时无代理商/合规配置信息，需允许为空
ALTER TABLE work_order
    MODIFY COLUMN `agent_id`     VARCHAR(36) NULL COMMENT '代理商ID（达尔文来源时为空）',
    MODIFY COLUMN `compliance_id` VARCHAR(36) NULL COMMENT '绑定合规配置ID（达尔文来源时为空）';

-- ============================================================
-- 回滚脚本
-- ============================================================
-- ALTER TABLE work_order DROP INDEX idx_source, DROP COLUMN source;
-- DROP TABLE IF EXISTS darwin_workorder_mapping;
-- ALTER TABLE work_order
--     MODIFY COLUMN `agent_id`      VARCHAR(36) NOT NULL COMMENT '代理商ID',
--     MODIFY COLUMN `compliance_id` VARCHAR(36) NOT NULL COMMENT '绑定合规配置ID';
