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
  `device_type`         TINYINT        NOT NULL DEFAULT 1   COMMENT '1-网关 2-传感器 3-控制器',
  `product_id`          VARCHAR(32)    DEFAULT NULL,
  `device_model`        VARCHAR(64)    DEFAULT NULL,
  `serial_number`       VARCHAR(64)    DEFAULT NULL,
  `firmware_version`    VARCHAR(32)    DEFAULT NULL,
  `status`              TINYINT        NOT NULL DEFAULT 0   COMMENT '0-未激活 1-在线 2-离线 3-禁用',
  `device_secret`       VARCHAR(128)   DEFAULT NULL         COMMENT '设备密钥(64位Hex)，MQTT连接密码，同时派生per-device AES Key',
  `secret_create_time`  DATETIME       DEFAULT NULL         COMMENT '密钥生成时间',
  `description`         VARCHAR(512)   DEFAULT NULL,
  `last_online_time`    DATETIME       DEFAULT NULL,
  `last_offline_time`   DATETIME       DEFAULT NULL,
  `longitude`           DECIMAL(10,7)  DEFAULT NULL,
  `latitude`            DECIMAL(10,7)  DEFAULT NULL,
  `create_by`           VARCHAR(64)    DEFAULT NULL,
  `tenant_id`           INT            DEFAULT 0,
  `create_time`         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`         DATETIME       DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`            TINYINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_code` (`device_code`),
  KEY `idx_status` (`status`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IoT设备基础信息';

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
  `raw_data`        TEXT          DEFAULT NULL,
  `report_time`     DATETIME      NOT NULL,
  `receive_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_id`    (`device_id`),
  KEY `idx_device_code`  (`device_code`),
  KEY `idx_receive_time` (`receive_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备状态上报历史';

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

-- 测试数据（device_secret需在平台控制台生成后下发给设备端）
INSERT INTO `iot_device` (id,device_code,device_name,device_type,product_id,device_model,firmware_version,status,device_secret,secret_create_time,description,create_by,tenant_id,create_time)
VALUES
  ('d001','DEV_001','温湿度传感器-01',2,'PROD_SENSOR_001','TH-200','1.0.0',0,'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',NOW(),'仓库A区','admin',1,NOW()),
  ('d002','DEV_002','网关设备-01',1,'PROD_GW_001','GW-100','2.1.0',0,'b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3',NOW(),'楼层1主网关','admin',1,NOW()),
  ('d003','DEV_003','控制器-01',3,'PROD_CTRL_001','CT-300','1.5.0',0,'c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4',NOW(),'A区空调控制器','admin',1,NOW());
