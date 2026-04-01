-- =========================================================
-- IoT设备管理模块 数据库初始化脚本
-- 鉴权方案：设备通过deviceSecret作为MQTT密码直连EMQX
--           EMQX HTTP Auth回调 /internal/mqtt/auth 完成连接层鉴权
--           消息体使用per-device AES-256-CBC加密
-- =========================================================
-- CREATE DATABASE IF NOT EXISTS iot_device_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- USE iot_device_db;

-- 1. 设备基础信息
CREATE TABLE IF NOT EXISTS `iot_device` (
  `id`                  VARCHAR(32)    NOT NULL COMMENT '设备ID（雪花）',
  `device_code`         VARCHAR(64)    NOT NULL COMMENT '设备编号（全局唯一，同时作为MQTT ClientId/Username）',
  `device_name`         VARCHAR(128)   NOT NULL COMMENT '设备名称',
  `device_type`         TINYINT        NOT NULL DEFAULT 1   COMMENT '1-机器人设备 2-主控设备',
  `product_id`          VARCHAR(32)    DEFAULT NULL,
  `device_model`        VARCHAR(64)    DEFAULT NULL,
  `serial_number`       VARCHAR(64)    DEFAULT NULL,
  `mac_address`         VARCHAR(64)    DEFAULT NULL COMMENT '设备网卡MAC地址',
  `firmware_version`    VARCHAR(32)    DEFAULT NULL,
  `status`              TINYINT        NOT NULL DEFAULT 0   COMMENT '0-未激活 1-在线 2-离线 3-禁用',
  `use_status`          TINYINT        NOT NULL DEFAULT 0   COMMENT '使用状态：0-空闲 1-占用（使用中）',
  `device_secret`       VARCHAR(128)   DEFAULT NULL         COMMENT '设备密钥(64位Hex)，MQTT连接密码，同时派生per-device AES Key',
  `secret_create_time`  DATETIME       DEFAULT NULL         COMMENT '密钥生成时间',
  `description`         VARCHAR(512)   DEFAULT NULL,
  `last_online_time`    DATETIME       DEFAULT NULL,
  `last_offline_time`   DATETIME       DEFAULT NULL,
  `last_login_time`     DATETIME       DEFAULT NULL COMMENT '主控设备最后一次登录时间',
  `longitude`           DECIMAL(10,7)  DEFAULT NULL,
  `latitude`            DECIMAL(10,7)  DEFAULT NULL,
  `address`             VARCHAR(256)   DEFAULT NULL COMMENT 'MQTT连接源地址(高德IP定位+逆地理后的行政区划文案；内网为内网IP)',
  `create_by`           VARCHAR(64)    DEFAULT NULL,
  `tenant_id`           INT            DEFAULT 0,
  `create_time`         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`         DATETIME       DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`            TINYINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_code` (`device_code`),
  KEY `idx_status` (`status`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_mac_address` (`mac_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IoT设备基础信息';

-- 1.1 设备授权表（主控/机器人授权给租户或用户）
CREATE TABLE IF NOT EXISTS `iot_device_auth` (
   `id` varchar(32) NOT NULL,
   `tenant_id` varchar(36)  DEFAULT NULL COMMENT '租户ID',
   `tenant_name` varchar(128) DEFAULT NULL COMMENT '租户名称（冗余）',
   `enterprise_id` varchar(36)  DEFAULT NULL COMMENT '企业ID',
   `enterprise_name` varchar(128)  DEFAULT NULL COMMENT '企业名称',
   `controller_id` varchar(32) NOT NULL COMMENT '主控设备ID',
   `controller_code` varchar(64)  NOT NULL COMMENT '主控设备编码',
   `device_id` varchar(32) NOT NULL COMMENT '机器人设备ID',
   `device_code` varchar(64)  NOT NULL COMMENT '机器人设备code',
   `admin_user_id` varchar(64)  DEFAULT NULL COMMENT '指定企业或租户id',
   `admin_username` varchar(64)  DEFAULT NULL COMMENT '指定企业或租户用户名称',
   `effective_time` datetime DEFAULT NULL COMMENT '生效时间',
   `expire_time` datetime DEFAULT NULL COMMENT '失效时间',
   `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-启用 0-禁用',
   `create_by` varchar(64) DEFAULT NULL,
   `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
   `update_by` varchar(64) DEFAULT NULL,
   `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
   `del_flag` tinyint NOT NULL DEFAULT '0',
   PRIMARY KEY (`id`),
   KEY `idx_controller_device` (`controller_id`,`device_id`),
   KEY `idx_status` (`status`),
   KEY `idx_tenant_enterprise` (`tenant_id`,`enterprise_id`),
   KEY `idx_controller_code` (`controller_code`),
   KEY `idx_tenant_controller` (`tenant_id`,`controller_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备授权表';

-- 1.2 主控端登录记录表
CREATE TABLE IF NOT EXISTS `iot_controller_login_log` (
  `id`                    VARCHAR(32)  NOT NULL,
  `controller_id`         VARCHAR(32)  NOT NULL COMMENT '主控设备ID',
  `controller_code`       VARCHAR(64)  NOT NULL,
  `operator_id`           VARCHAR(64)  DEFAULT NULL,
  `operator_name`         VARCHAR(64)  DEFAULT NULL,
  `associated_robot_id`  VARCHAR(32)  DEFAULT NULL,
  `associated_robot_code` VARCHAR(64)  DEFAULT NULL,
  `login_time`            DATETIME    NOT NULL,
  `create_time`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_controller_id` (`controller_id`),
  KEY `idx_login_time`   (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主控端登录记录';

-- 2. 设备参数配置
CREATE TABLE IF NOT EXISTS `iot_device_config` (
  `id`           VARCHAR(32)   NOT NULL,
  `device_id`    VARCHAR(32)   NOT NULL,
  `device_code`  VARCHAR(64)   NOT NULL,
  `config_key`   VARCHAR(64)   NOT NULL,
  `config_value` VARCHAR(1024) NOT NULL,
  `config_type`  VARCHAR(16)   DEFAULT 'string',
  `sync_status`  TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待同步 1-成功 2-失败',
  `sync_time`    DATETIME      DEFAULT NULL,
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_cfg` (`device_id`,`config_key`),
  KEY `idx_device_code` (`device_code`),
  KEY `idx_sync_status` (`sync_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备参数配置';

-- 3. 设备状态上报历史（按季度分区）
CREATE TABLE IF NOT EXISTS `iot_device_status` (
  `id`              VARCHAR(32)   NOT NULL,
  `device_id`       VARCHAR(32)   NOT NULL,
  `device_code`     VARCHAR(64)   NOT NULL,
  `temperature`     DECIMAL(6,2)  DEFAULT NULL,
  `humidity`        DECIMAL(6,2)  DEFAULT NULL,
  `battery_level`   DECIMAL(5,2)  DEFAULT NULL,
  `signal_strength` INT           DEFAULT NULL,
  `longitude`       DECIMAL(10,7) DEFAULT NULL,
  `latitude`        DECIMAL(10,7) DEFAULT NULL,
  `run_status`      TINYINT       DEFAULT 1 COMMENT '1-正常 2-告警 3-故障',
  `raw_data`        MEDIUMTEXT    DEFAULT NULL COMMENT '原始上报 JSON',
  `report_time`     DATETIME      NOT NULL,
  `receive_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_id`    (`device_id`),
  KEY `idx_device_code`  (`device_code`),
  KEY `idx_receive_time` (`receive_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备状态上报历史';

-- 3.1 设备状态上报历史归档表（用于长期归档 iot_device_status 旧数据）
CREATE TABLE IF NOT EXISTS `iot_device_status_history` (
  `id`              VARCHAR(32)   NOT NULL COMMENT '与主表一致的流水ID',
  `device_id`       VARCHAR(32)   NOT NULL COMMENT '设备ID',
  `device_code`     VARCHAR(64)   NOT NULL COMMENT '设备编号',
  `temperature`     DECIMAL(6,2)  DEFAULT NULL COMMENT '温度',
  `humidity`        DECIMAL(6,2)  DEFAULT NULL COMMENT '湿度',
  `battery_level`   DECIMAL(5,2)  DEFAULT NULL COMMENT '电量百分比',
  `signal_strength` INT           DEFAULT NULL COMMENT '信号强度',
  `longitude`       DECIMAL(10,7) DEFAULT NULL COMMENT '经度',
  `latitude`        DECIMAL(10,7) DEFAULT NULL COMMENT '纬度',
  `run_status`      TINYINT       DEFAULT 1 COMMENT '1-正常 2-告警 3-故障',
  `raw_data`        MEDIUMTEXT    DEFAULT NULL COMMENT '原始上报数据（大 JSON 用 MEDIUMTEXT）',
  `report_time`     DATETIME      NOT NULL COMMENT '设备上报时间',
  `receive_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '平台接收时间',
  `backup_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
  PRIMARY KEY (`id`),
  KEY `idx_hist_device_id`    (`device_id`),
  KEY `idx_hist_device_code`  (`device_code`),
  KEY `idx_hist_report_time`  (`report_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备状态上报历史归档表';

-- 4. 设备操作日志
CREATE TABLE IF NOT EXISTS `iot_device_operation_log` (
  `id`               VARCHAR(32)  NOT NULL,
  `device_id`        VARCHAR(32)  DEFAULT NULL,
  `device_code`      VARCHAR(64)  NOT NULL,
  `operation_type`   VARCHAR(32)  NOT NULL,
  `operation_desc`   VARCHAR(512) NOT NULL,
  `operation_detail` TEXT         DEFAULT NULL,
  `operation_source` VARCHAR(16)  DEFAULT 'PLATFORM' COMMENT 'PLATFORM/DEVICE',
  `operation_result` VARCHAR(16)  DEFAULT 'SUCCESS'  COMMENT 'SUCCESS/FAIL/PENDING',
  `fail_reason`      VARCHAR(256) DEFAULT NULL,
  `operator`         VARCHAR(64)  DEFAULT NULL,
  `client_ip`        VARCHAR(64)  DEFAULT NULL,
  `create_time`      DATETIME     NOT NULL,
  `operation_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_code`    (`device_code`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备操作日志';

-- 5. OTA固件包
CREATE TABLE IF NOT EXISTS `iot_ota_firmware` (
  `id`            VARCHAR(32)   NOT NULL,
  `firmware_name` VARCHAR(128)  NOT NULL,
  `version`       VARCHAR(32)   NOT NULL,
  `product_id`    VARCHAR(32)   NOT NULL,
  `file_path`     VARCHAR(512)  NOT NULL COMMENT 'MinIO对象路径',
  `file_name`     VARCHAR(256)  NOT NULL,
  `file_size`     BIGINT        DEFAULT NULL COMMENT '字节数，设备断点续传时计算进度',
  `file_md5`      VARCHAR(64)   NOT NULL     COMMENT '设备下载完成后校验完整性',
  `download_url`  VARCHAR(1024) DEFAULT NULL COMMENT 'MinIO预签名URL，设备用此URL的HTTP Range断点续传',
  `description`   VARCHAR(512)  DEFAULT NULL,
  `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '0-草稿 1-发布 2-禁用',
  `force_upgrade` TINYINT       NOT NULL DEFAULT 0,
  `create_by`     VARCHAR(64)   DEFAULT NULL,
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`      TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_ver` (`product_id`,`version`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA固件包';

-- 6. OTA升级任务
CREATE TABLE IF NOT EXISTS `iot_ota_upgrade_task` (
  `id`                VARCHAR(32)  NOT NULL,
  `task_name`         VARCHAR(128) NOT NULL,
  `firmware_id`       VARCHAR(32)  NOT NULL,
  `firmware_version`  VARCHAR(32)  NOT NULL,
  `task_status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待执行 1-执行中 2-完成 3-部分成功 4-已取消',
  `upgrade_type`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-单设备 2-批量',
  `total_count`       INT          NOT NULL DEFAULT 0,
  `success_count`     INT          NOT NULL DEFAULT 0,
  `fail_count`        INT          NOT NULL DEFAULT 0,
  `upgrading_count`   INT          NOT NULL DEFAULT 0,
  `actual_start_time` DATETIME     DEFAULT NULL,
  `finish_time`       DATETIME     DEFAULT NULL,
  `create_by`         VARCHAR(64)  DEFAULT NULL,
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_status` (`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA升级任务';

-- 7. OTA设备升级记录（每台设备一条）
CREATE TABLE IF NOT EXISTS `iot_ota_upgrade_record` (
  `id`               VARCHAR(32)  NOT NULL,
  `task_id`          VARCHAR(32)  NOT NULL,
  `device_id`        VARCHAR(32)  NOT NULL,
  `device_code`      VARCHAR(64)  NOT NULL,
  `firmware_id`      VARCHAR(32)  NOT NULL,
  `old_version`      VARCHAR(32)  DEFAULT NULL,
  `target_version`   VARCHAR(32)  NOT NULL,
  `upgrade_status`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待升级 1-已通知 2-已确认 3-下载中 4-下载完成 5-安装中 6-成功 7-失败 8-超时',
  `download_progress` TINYINT     NOT NULL DEFAULT 0 COMMENT '下载进度0-100',
  `downloaded_bytes` BIGINT       NOT NULL DEFAULT 0 COMMENT '已下载字节数（断点续传核心字段）',
  `fail_reason`      VARCHAR(512) DEFAULT NULL,
  `notify_time`      DATETIME     DEFAULT NULL,
  `start_time`       DATETIME     DEFAULT NULL,
  `finish_time`      DATETIME     DEFAULT NULL,
  `retry_count`      TINYINT      NOT NULL DEFAULT 0,
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id`       (`task_id`),
  KEY `idx_device_code`   (`device_code`),
  KEY `idx_upgrade_status`(`upgrade_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA设备升级记录';

-- 8. 工单合规配置
CREATE TABLE IF NOT EXISTS `work_order_compliance_config` (
  `id`                        VARCHAR(36)  NOT NULL PRIMARY KEY COMMENT '配置ID',
  `agent_id`                  VARCHAR(36)  NOT NULL COMMENT '代理商ID',
  `agent_name`                VARCHAR(100)          COMMENT '代理商名称',
  `enterprise_id`             VARCHAR(36)           COMMENT '企业ID',
  `enterprise_name`           VARCHAR(100)          COMMENT '企业名称',
  `task_scene`                VARCHAR(100)          COMMENT '任务场景',
  `timeout_alert_enabled`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否自动预警:0-禁用 1-启用',
  `timeout_alert_offset`      VARCHAR(8)            COMMENT '自动预警配置时间（距任务结束前X，H:M:S）',
  `task_limit_enabled`        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否有任务时限:0-否 1-是（默认启动）',
  `acceptance_enabled`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '工单是否需要验收:0-禁用 1-启用',
  `overtime_enabled`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否启用超时提交:0-禁用 1-启用',
  `overtime_reason_enum`      VARCHAR(20)          COMMENT '超时提交原因枚举（用户原因/节假日/设备故障）',
  `overtime_reason_desc`      VARCHAR(500)          COMMENT '超时提交描述',
  `auto_close_enabled`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '超时未提交策略:0-禁用 1-启用',
  `auto_close_offset`         VARCHAR(8)            COMMENT '启用超时未提交策略时设置的超时时长（H:M:S）',
  `apply_status`              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '应用状态:0-未应用 1-已应用（有工单绑定时为已应用）',
  `create_by`                 VARCHAR(50)           COMMENT '创建人',
  `create_time`               DATETIME              COMMENT '创建时间',
  `update_by`                 VARCHAR(50)           COMMENT '修改人',
  `update_time`               DATETIME              COMMENT '修改时间',
  `del_flag`                  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-正常 1-删除',
  `tenant_id`                 VARCHAR(36)           COMMENT '租户ID'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单合规配置';

-- 9. 工单主表
CREATE TABLE IF NOT EXISTS `work_order` (
  `id`                    VARCHAR(36)  NOT NULL PRIMARY KEY COMMENT '工单ID',
  `task_name`             VARCHAR(200) NOT NULL COMMENT '工单任务名称',
  `agent_id`              VARCHAR(36)  NOT NULL COMMENT '代理商ID',
  `agent_name`            VARCHAR(100)          COMMENT '代理商名称',
  `department_id`         VARCHAR(36)           COMMENT '所属部门ID',
  `department_name`       VARCHAR(100)          COMMENT '所属部门名称',
  `compliance_id`         VARCHAR(36)  NOT NULL COMMENT '绑定合规配置ID',
  `currency`              VARCHAR(10)           COMMENT '币种（如CNY/USD）',
  `unit_price`            DECIMAL(10,2)         COMMENT '单价',
  `total_price`           DECIMAL(10,2)         COMMENT '总价',
  `remark`                VARCHAR(500)          COMMENT '备注',
  `plan_start_time`       DATETIME     NOT NULL COMMENT '计划开始时间',
  `plan_end_time`         DATETIME     NOT NULL COMMENT '计划结束时间（失效时间）',
  `status`                VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/STARTED/SUBMITTED/COMPLETED/TIMEOUT/CLOSED',
  `audit_result`          VARCHAR(20)           COMMENT '审核结果: 0-不合格，1-合格',
  `operator_id`           VARCHAR(36)           COMMENT '开启人员ID',
  `operator_name`         VARCHAR(50)           COMMENT '开启人员姓名',
  `operator_phone`        VARCHAR(20)           COMMENT '开启人员联系方式',
  `actual_start_time`     DATETIME              COMMENT '实际开启时间',
  `submit_time`           DATETIME              COMMENT '提交时间',
  `timeout_reason`        VARCHAR(100)          COMMENT '超时原因',
  `timeout_reason_source` VARCHAR(20)           COMMENT '原因来源: USER/SYSTEM',
  `audit_by`              VARCHAR(50)           COMMENT '审核人',
  `audit_time`            DATETIME              COMMENT '审核时间',
  `audit_comment`         VARCHAR(200)          COMMENT '审核意见',
  `close_by`              VARCHAR(50)           COMMENT '关闭人',
  `close_time`            DATETIME              COMMENT '关闭时间',
  `close_reason`          VARCHAR(200)          COMMENT '关闭原因',
  `create_by`             VARCHAR(50)           COMMENT '创建人',
  `create_time`           DATETIME              COMMENT '创建时间',
  `update_by`             VARCHAR(50)           COMMENT '修改人',
  `update_time`           DATETIME              COMMENT '修改时间',
  `del_flag`              TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `tenant_id`             VARCHAR(36)           COMMENT '租户ID'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单主表';

-- 10. 工单绑定设备
CREATE TABLE IF NOT EXISTS `work_order_device` (
  `id`                  VARCHAR(36)  NOT NULL PRIMARY KEY,
  `work_order_id`       VARCHAR(36)  NOT NULL COMMENT '工单ID',
  `device_type`         VARCHAR(20)  NOT NULL COMMENT '设备类型: CONTROLLER/ROBOT',
  `device_id`           VARCHAR(36)  NOT NULL COMMENT '计划设备ID',
  `device_name`         VARCHAR(100)          COMMENT '计划设备名称',
  `device_code`         VARCHAR(100)          COMMENT '计划设备编号',
  `actual_device_id`    VARCHAR(36)           COMMENT '实际使用设备ID',
  `actual_device_name`  VARCHAR(100)          COMMENT '实际使用设备名称',
  `actual_device_code`  VARCHAR(100)          COMMENT '实际使用设备编号',
  `create_time`         DATETIME              COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单绑定设备';

-- 11. 工单佐证图片
CREATE TABLE IF NOT EXISTS `work_order_attachment` (
  `id`            VARCHAR(36)   NOT NULL PRIMARY KEY,
  `work_order_id` VARCHAR(36)   NOT NULL COMMENT '工单ID',
  `file_url`      VARCHAR(500)  NOT NULL COMMENT '图片URL',
  `file_name`     VARCHAR(200)           COMMENT '图片文件名',
  `description`   VARCHAR(200)           COMMENT '图片说明',
  `create_by`     VARCHAR(50)            COMMENT '上传人',
  `create_time`   DATETIME               COMMENT '上传时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单佐证图片';

-- 12. 主控遥操操作记录（操作记录：遥操员用主控操控机器人完成工单的时间）
CREATE TABLE IF NOT EXISTS `controller_operation_record` (
  `id`                VARCHAR(36)  NOT NULL PRIMARY KEY COMMENT '记录ID',
  `controller_id`    VARCHAR(36)  NOT NULL COMMENT '主控设备ID',
  `controller_code`  VARCHAR(64)  NOT NULL COMMENT '主控设备编号',
  `robot_id`          VARCHAR(36)  NOT NULL COMMENT '机器人设备ID',
  `robot_code`       VARCHAR(64)  NOT NULL COMMENT '机器人设备编号',
  `operator_id`       VARCHAR(64)           COMMENT '遥操员ID',
  `operator_name`     VARCHAR(64)           COMMENT '遥操员姓名',
  `work_order_id`     VARCHAR(36)  NOT NULL COMMENT '工单ID',
  `start_time`        DATETIME     NOT NULL COMMENT '开始操作时间（= 工单开启时间）',
  `end_time`          DATETIME              COMMENT '结束操作时间（正常=提交时间，异常=工单失效时间）',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME              ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_controller_id` (`controller_id`),
  KEY `idx_robot_id`     (`robot_id`),
  KEY `idx_work_order_id` (`work_order_id`),
  KEY `idx_start_time`   (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主控遥操操作记录';

-- 13. SLAM地图元数据
CREATE TABLE IF NOT EXISTS `iot_slam_map` (
  `id`                VARCHAR(32)   NOT NULL COMMENT '地图ID',
  `tenant_id`         VARCHAR(36)   DEFAULT NULL COMMENT '租户ID',
  `enterprise_id`     VARCHAR(36)   DEFAULT NULL COMMENT '企业/部门ID',
  `map_name`          VARCHAR(128)  NOT NULL COMMENT '地图名称',
  `map_version`       VARCHAR(64)   DEFAULT NULL COMMENT '地图版本号',
  `source_robot_id`   VARCHAR(36)   NOT NULL COMMENT '来源机器人ID',
  `source_robot_code` VARCHAR(64)   NOT NULL COMMENT '来源机器人编码',
  `file_object_key`   VARCHAR(512)  NOT NULL COMMENT 'MinIO对象路径',
  `file_md5`          VARCHAR(64)   DEFAULT NULL COMMENT '文件MD5',
  `file_size`         BIGINT        DEFAULT NULL COMMENT '文件大小（字节）',
  `status`            TINYINT       NOT NULL DEFAULT 0 COMMENT '0-UPLOADING 1-READY 2-DELETED',
  `create_by`         VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`          TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_source_robot` (`source_robot_id`),
  KEY `idx_tenant_enterprise` (`tenant_id`,`enterprise_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLAM地图元数据';

-- 14. 机器人与SLAM地图绑定关系
CREATE TABLE IF NOT EXISTS `iot_robot_slam_binding` (
  `id`               VARCHAR(32)   NOT NULL COMMENT '绑定ID',
  `tenant_id`        VARCHAR(36)   DEFAULT NULL COMMENT '租户ID',
  `enterprise_id`    VARCHAR(36)   DEFAULT NULL COMMENT '企业/部门ID',
  `robot_id`         VARCHAR(36)   NOT NULL COMMENT '机器人ID',
  `robot_code`       VARCHAR(64)   NOT NULL COMMENT '机器人编码',
  `slam_map_id`      VARCHAR(32)   NOT NULL COMMENT 'SLAM地图ID',
  `state`            TINYINT       NOT NULL DEFAULT 0 COMMENT '0-PENDING 1-ACTIVE 2-OBSOLETE 3-FAILED',
  `pending_task_id`  VARCHAR(32)   DEFAULT NULL COMMENT '同步任务ID',
  `effective_time`   DATETIME      DEFAULT NULL COMMENT '生效时间',
  `obsolete_time`    DATETIME      DEFAULT NULL COMMENT '作废时间',
  `fail_reason`      VARCHAR(512)  DEFAULT NULL COMMENT '失败原因',
  `create_by`        VARCHAR(64)   DEFAULT NULL,
  `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`         TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_robot_state` (`robot_id`,`state`),
  KEY `idx_slam_map` (`slam_map_id`),
  KEY `idx_pending_task` (`pending_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机器人SLAM绑定关系';

-- 15. SLAM同步任务
CREATE TABLE IF NOT EXISTS `iot_slam_sync_task` (
  `id`                VARCHAR(32)   NOT NULL COMMENT '任务ID',
  `tenant_id`         VARCHAR(36)   DEFAULT NULL COMMENT '租户ID',
  `enterprise_id`     VARCHAR(36)   DEFAULT NULL COMMENT '企业/部门ID',
  `source_robot_id`   VARCHAR(36)   NOT NULL COMMENT '来源机器人ID',
  `source_robot_code` VARCHAR(64)   NOT NULL COMMENT '来源机器人编码',
  `slam_map_id`       VARCHAR(32)   NOT NULL COMMENT 'SLAM地图ID',
  `target_robot_ids`  TEXT          DEFAULT NULL COMMENT '目标机器人ID列表(JSON)',
  `total_count`       INT           NOT NULL DEFAULT 0 COMMENT '目标总数',
  `success_count`     INT           NOT NULL DEFAULT 0 COMMENT '成功数',
  `fail_count`        INT           NOT NULL DEFAULT 0 COMMENT '失败数',
  `status`            TINYINT       NOT NULL DEFAULT 0 COMMENT '0-RUNNING 1-SUCCESS 2-PARTIAL_FAIL 3-FAIL',
  `create_by`         VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
  `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_source_robot` (`source_robot_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLAM同步任务';

-- SLAM 指令请求/响应记录表
-- 每次平台向设备发送 device/{code}/slam/request 时创建一条记录
-- 收到 device/{code}/slam/ack 后更新对应状态
CREATE TABLE IF NOT EXISTS `iot_slam_command_record` (
                                                         `id`            VARCHAR(32)     NOT NULL                    COMMENT '主键（雪花ID）',
                                                         `device_code`   VARCHAR(64)     NOT NULL                    COMMENT '设备编码',
                                                         `command_id`    VARCHAR(64)     NOT NULL                    COMMENT '请求唯一标识（下发到设备的 commandId）',
                                                         `function_name` VARCHAR(64)     NOT NULL                    COMMENT '功能代码（SwitchMode/GetCurrentMap/SaveMap/SinglePointNavigation/MultiWaypointNavigation/SetInitialPose）',
                                                         `params_json`   TEXT                                        COMMENT '请求参数 JSON',
                                                         `status`        VARCHAR(16)     NOT NULL DEFAULT 'PENDING'  COMMENT '状态：PENDING（已发送等待响应）/ PARTIAL（部分响应）/ COMPLETED（成功完成）/ FAILED（失败）',
                                                         `ack_success`   TINYINT(1)                                  COMMENT 'ack 响应 success 字段',
                                                         `ack_code`      INT                                         COMMENT 'ack 响应 code 字段（0=成功）',
                                                         `ack_message`   VARCHAR(512)                                COMMENT 'ack 响应 message 字段',
                                                         `ack_sequence`  INT                                         COMMENT '当前已收到的最大响应序号',
                                                         `ack_total`     INT                                         COMMENT '本次请求预期总响应次数',
                                                         `ack_data_json` MEDIUMTEXT                                  COMMENT '最终 ack 的 data JSON（最后一次响应的 data 字段）',
                                                         `send_time`     DATETIME        NOT NULL                    COMMENT '指令发送时间',
                                                         `complete_time` DATETIME                                    COMMENT '完成时间（收到最终响应或失败响应）',
                                                         `create_time`   DATETIME                                    COMMENT '创建时间',
                                                         `update_time`   DATETIME                                    COMMENT '更新时间',
                                                         PRIMARY KEY (`id`),
                                                         UNIQUE KEY `uk_command_id` (`command_id`),
                                                         KEY `idx_device_code_send_time` (`device_code`, `send_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLAM 指令请求/响应记录';

-- 测试数据（device_secret需在平台控制台生成后下发给设备端）
INSERT INTO `iot_device` (id,device_code,device_name,device_type,product_id,device_model,firmware_version,status,device_secret,secret_create_time,description,create_by,tenant_id,create_time)
VALUES
  ('d001','DEV_001','温湿度传感器-01',2,'PROD_SENSOR_001','TH-200','1.0.0',0,'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',NOW(),'仓库A区','admin',1,NOW()),
  ('d002','DEV_002','网关设备-01',1,'PROD_GW_001','GW-100','2.1.0',0,'b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3',NOW(),'楼层1主网关','admin',1,NOW()),
  ('d003','DEV_003','控制器-01',3,'PROD_CTRL_001','CT-300','1.5.0',0,'c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4',NOW(),'A区空调控制器','admin',1,NOW());

-- 已有库升级：若表已存在但无 address 列，可执行（重复执行会报错，需自行判断）
-- ALTER TABLE `iot_device` ADD COLUMN `address` VARCHAR(256) DEFAULT NULL COMMENT 'MQTT连接行政区划(高德IP定位)' AFTER `latitude`;
-- 已有库升级：若表已存在但无 use_status 列，可执行
-- ALTER TABLE `iot_device` ADD COLUMN `use_status` TINYINT NOT NULL DEFAULT 0 COMMENT '使用状态：0-空闲 1-占用（使用中）' AFTER `status`;
