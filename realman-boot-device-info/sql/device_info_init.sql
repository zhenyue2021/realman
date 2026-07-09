-- 设备信息基础服务（SSOT）建表脚本
-- 对齐 docs/design/2026-07-08-device-foundation-detailed-design.md 第二章 2.1 数据模型

CREATE TABLE `device_info` (
  `device_id`            varchar(36)  NOT NULL COMMENT '内部唯一标识（UUID），注册时生成，终身不变',
  `tenant_id`             varchar(32)  NOT NULL COMMENT '所属租户，创建后不可变',
  `device_code`           varchar(64)  NOT NULL COMMENT '设备序列号 / 通信层标识（MQTT clientId），产线生成，全局唯一',
  `device_type`           varchar(20)  NOT NULL COMMENT 'MASTER / SLAVE / SMART_ARM',
  `device_model`          varchar(64)  DEFAULT NULL COMMENT '型号',
  `device_name`           varchar(128) DEFAULT NULL COMMENT '展示名称',
  `mac_address`           varchar(32)  DEFAULT NULL COMMENT '网络硬件地址',
  `ip_address`            varchar(64)  DEFAULT NULL COMMENT '最近一次上报的 IP（心跳同步）',
  `firmware_version`      varchar(32)  DEFAULT NULL COMMENT '固件版本（master/slave 单一版本号，统一大写 V 格式）',
  `firmware_components`   varchar(512) DEFAULT NULL COMMENT '多组件版本 JSON（Smart Arm 专用：app/model/fw）',
  `online_status`         varchar(16)  NOT NULL DEFAULT 'UNACTIVATED' COMMENT 'UNACTIVATED / ONLINE / OFFLINE',
  `occupancy_state`       varchar(16)  NOT NULL DEFAULT 'OFFLINE' COMMENT 'IDLE / SLEEP / OCCUPIED / OFFLINE（四态）',
  `occupancy_detail`      varchar(16)  DEFAULT NULL COMMENT 'OCCUPIED 态细分：TELEOP / LOCAL / AUTONOMOUS',
  `lifecycle_stage`       varchar(16)  NOT NULL DEFAULT 'MANUFACTURED' COMMENT 'MANUFACTURED / ACTIVATED / RUNNING / MAINTENANCE / RETIRED',
  `is_test_device`        tinyint(1)   NOT NULL DEFAULT 0 COMMENT '测试设备标记，由设备管理业务平台写入',
  `location`              varchar(512) DEFAULT NULL COMMENT '位置信息 JSON（国家/城市/区/街道/楼宇 + 经纬度）',
  `last_heartbeat_at`     datetime     DEFAULT NULL COMMENT '最近心跳时间',
  `last_online_at`        datetime     DEFAULT NULL COMMENT '最近上线时间',
  `last_offline_at`       datetime     DEFAULT NULL COMMENT '最近下线时间',
  `offline_reason`        varchar(64)  DEFAULT NULL COMMENT '离线原因，如 KEEPALIVE_TIMEOUT',
  `bound_device_ids`      varchar(1024) DEFAULT NULL COMMENT '主控端 ↔ 机器人绑定关系快照 JSON 数组（读优化，权威数据在设备管理业务平台）',
  `component_sn_map`      varchar(512) DEFAULT NULL COMMENT '部件级 SN JSON（臂/底盘/主控）',
  `data_version`          bigint       NOT NULL DEFAULT 0 COMMENT '乐观锁 / 变更版本号',
  `created_at`            datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            datetime     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`device_id`),
  UNIQUE KEY `uk_device_code` (`device_code`),
  KEY `idx_tenant_type_model` (`tenant_id`, `device_type`, `device_model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备信息基础服务（SSOT）读优化投影';
