-- `realman-boot`.controller_operation_record 定义

CREATE TABLE `controller_operation_record` (
                                               `id` varchar(36) NOT NULL COMMENT '记录ID',
                                               `controller_id` varchar(36) NOT NULL COMMENT '主控设备ID',
                                               `controller_code` varchar(64) NOT NULL COMMENT '主控设备编号',
                                               `robot_id` varchar(36) NOT NULL COMMENT '机器人设备ID',
                                               `robot_code` varchar(64) NOT NULL COMMENT '机器人设备编号',
                                               `operator_id` varchar(64) DEFAULT NULL COMMENT '遥操员ID',
                                               `operator_name` varchar(64) DEFAULT NULL COMMENT '遥操员姓名',
                                               `work_order_id` varchar(36) NOT NULL COMMENT '工单ID',
                                               `start_time` datetime NOT NULL COMMENT '开始操作时间（= 工单开启时间）',
                                               `end_time` datetime DEFAULT NULL COMMENT '结束操作时间（正常=提交时间，异常=工单失效时间）',
                                               `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                                               PRIMARY KEY (`id`),
                                               KEY `idx_controller_id` (`controller_id`),
                                               KEY `idx_robot_id` (`robot_id`),
                                               KEY `idx_work_order_id` (`work_order_id`),
                                               KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主控遥操操作记录';


-- `realman-boot`.iot_controller_login_log 定义

CREATE TABLE `iot_controller_login_log` (
                                            `id` varchar(32) NOT NULL,
                                            `controller_id` varchar(32) NOT NULL COMMENT '主控设备ID',
                                            `controller_code` varchar(64) NOT NULL,
                                            `operator_id` varchar(64) DEFAULT NULL,
                                            `operator_name` varchar(64) DEFAULT NULL,
                                            `associated_robot_id` varchar(32) DEFAULT NULL,
                                            `associated_robot_code` varchar(64) DEFAULT NULL,
                                            `login_time` datetime NOT NULL,
                                            `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                            PRIMARY KEY (`id`),
                                            KEY `idx_controller_id` (`controller_id`),
                                            KEY `idx_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主控端登录记录';


-- `realman-boot`.iot_device 定义

CREATE TABLE `iot_device` (
                              `id` varchar(32) NOT NULL COMMENT '设备ID（雪花）',
                              `device_code` varchar(64) NOT NULL COMMENT '设备编号（全局唯一，同时作为MQTT ClientId/Username）',
                              `device_name` varchar(128) NOT NULL COMMENT '设备名称',
                              `device_type` tinyint NOT NULL DEFAULT '1' COMMENT '1-机器人设备 2-主控设备',
                              `product_id` varchar(32) DEFAULT NULL,
                              `device_model` varchar(64) DEFAULT NULL,
                              `serial_number` varchar(64) DEFAULT NULL,
                              `mac_address` varchar(64) DEFAULT NULL COMMENT '设备网卡MAC地址',
                              `firmware_version` varchar(32) DEFAULT NULL,
                              `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-未激活 1-在线 2-离线 3-禁用',
                              `use_status` tinyint NOT NULL DEFAULT '0' COMMENT '使用状态：0-空闲 1-占用（使用中）',
                              `device_secret` varchar(128) DEFAULT NULL COMMENT '设备密钥(64位Hex)，MQTT连接密码，同时派生per-device AES Key',
                              `secret_create_time` datetime DEFAULT NULL COMMENT '密钥生成时间',
                              `description` varchar(512) DEFAULT NULL,
                              `last_online_time` datetime DEFAULT NULL,
                              `last_offline_time` datetime DEFAULT NULL,
                              `last_login_time` datetime DEFAULT NULL COMMENT '主控设备最后一次登录时间',
                              `longitude` decimal(10,7) DEFAULT NULL,
                              `latitude` decimal(10,7) DEFAULT NULL,
                              `address` varchar(256) DEFAULT NULL COMMENT 'MQTT连接源地址(高德IP定位+逆地理后的行政区划文案；内网为内网IP)',
                              `create_by` varchar(64) DEFAULT NULL,
                              `tenant_id` int DEFAULT '0',
                              `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                              `del_flag` tinyint NOT NULL DEFAULT '0',
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `iot_device_unique` (`device_code`,`del_flag`),
                              KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IoT设备基础信息';


-- `realman-boot`.iot_device_auth 定义

CREATE TABLE `iot_device_auth` (
                                   `id` varchar(32) NOT NULL,
                                   `tenant_id` varchar(36) DEFAULT NULL COMMENT '租户ID',
                                   `tenant_name` varchar(128) DEFAULT NULL COMMENT '租户名称（冗余）',
                                   `enterprise_id` varchar(36) DEFAULT NULL COMMENT '企业ID',
                                   `enterprise_name` varchar(128) DEFAULT NULL COMMENT '企业名称',
                                   `controller_id` varchar(32) NOT NULL COMMENT '主控设备ID',
                                   `controller_code` varchar(64) NOT NULL COMMENT '主控设备编码',
                                   `device_id` varchar(32) NOT NULL COMMENT '机器人设备ID',
                                   `device_code` varchar(64) NOT NULL COMMENT '机器人设备code',
                                   `admin_user_id` varchar(64) DEFAULT NULL COMMENT '指定企业或租户id',
                                   `admin_username` varchar(64) DEFAULT NULL COMMENT '指定企业或租户用户名称',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备授权表';


-- `realman-boot`.iot_device_command_record 定义

CREATE TABLE `iot_device_command_record` (
                                             `id` varchar(32) NOT NULL COMMENT '主键（雪花ID）',
                                             `command_id` varchar(32) NOT NULL COMMENT '指令唯一ID，与 MQTT payload 中 commandId 一致',
                                             `device_id` varchar(32) DEFAULT NULL COMMENT '设备ID',
                                             `device_code` varchar(64) NOT NULL COMMENT '设备编号',
                                             `command_type` varchar(32) NOT NULL COMMENT '指令类型：restart/emergency-stop/poweroff/reset/force-feedback/sport-speed 等',
                                             `device_type` varchar(16) NOT NULL DEFAULT 'device' COMMENT '设备角色：device（机器人）/ master（主控）',
                                             `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '指令状态：PENDING/SUCCESS/FAIL/TIMEOUT',
                                             `fail_reason` varchar(256) DEFAULT NULL COMMENT '失败原因（设备返回的 message）',
                                             `operator` varchar(64) DEFAULT NULL COMMENT '操作人（设备主动查询时为 null）',
                                             `params_json` text COMMENT '下发指令的明文 JSON（不存 AES 密文）',
                                             `ack_data_json` text COMMENT '设备回复的完整明文 JSON',
                                             `send_time` datetime NOT NULL COMMENT '指令下发时间',
                                             `ack_time` datetime DEFAULT NULL COMMENT '收到设备 ACK 的时间（可与 send_time 差值算 RTT）',
                                             `create_time` datetime NOT NULL COMMENT '记录创建时间',
                                             `update_time` datetime DEFAULT NULL COMMENT '记录最后更新时间',
                                             PRIMARY KEY (`id`),
                                             UNIQUE KEY `uk_command_id` (`command_id`),
                                             KEY `idx_device_code` (`device_code`),
                                             KEY `idx_status_send_time` (`status`,`send_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备指令下发/ACK记录（双向通信生命周期追踪）';


-- `realman-boot`.iot_device_config 定义

CREATE TABLE `iot_device_config` (
                                     `id` varchar(32) NOT NULL,
                                     `device_id` varchar(32) NOT NULL,
                                     `device_code` varchar(64) NOT NULL,
                                     `config_key` varchar(64) NOT NULL,
                                     `config_value` varchar(1024) NOT NULL,
                                     `config_type` varchar(16) DEFAULT 'string',
                                     `sync_status` tinyint NOT NULL DEFAULT '0' COMMENT '0-待同步 1-成功 2-失败',
                                     `sync_time` datetime DEFAULT NULL,
                                     `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                                     PRIMARY KEY (`id`),
                                     UNIQUE KEY `uk_device_cfg` (`device_id`,`config_key`),
                                     KEY `idx_device_code` (`device_code`),
                                     KEY `idx_sync_status` (`sync_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备参数配置';


-- `realman-boot`.iot_device_operation_log 定义

CREATE TABLE `iot_device_operation_log` (
                                            `id` varchar(32) NOT NULL,
                                            `device_id` varchar(32) DEFAULT NULL,
                                            `device_code` varchar(64) NOT NULL,
                                            `operation_type` varchar(32) NOT NULL,
                                            `operation_desc` varchar(512) NOT NULL,
                                            `operation_detail` text,
                                            `operation_source` varchar(16) DEFAULT 'PLATFORM' COMMENT 'PLATFORM/DEVICE',
                                            `operation_result` varchar(16) DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAIL/PENDING',
                                            `fail_reason` varchar(256) DEFAULT NULL,
                                            `operator` varchar(64) DEFAULT NULL,
                                            `client_ip` varchar(64) DEFAULT NULL,
                                            `create_time` datetime NOT NULL,
                                            `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                            PRIMARY KEY (`id`),
                                            KEY `idx_device_code` (`device_code`),
                                            KEY `idx_operation_type` (`operation_type`),
                                            KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备操作日志';


-- `realman-boot`.iot_device_room 定义

CREATE TABLE `iot_device_room` (
                                   `id` varchar(32) NOT NULL COMMENT '房间号',
                                   `master_code` varchar(64) NOT NULL COMMENT '主控设备编码',
                                   `robot_code` varchar(64) DEFAULT NULL COMMENT '机器人设备编码（遥操开始时写入）',
                                   `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=等待中 1=遥操中 2=已销毁',
                                   `create_time` datetime NOT NULL COMMENT '创建时间',
                                   `destroy_time` datetime DEFAULT NULL COMMENT '销毁时间',
                                   `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0=正常 1=已删除',
                                   PRIMARY KEY (`id`),
                                   KEY `idx_master_code` (`master_code`),
                                   KEY `idx_robot_code` (`robot_code`),
                                   KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IoT 设备房间';


-- `realman-boot`.iot_device_status 定义

CREATE TABLE `iot_device_status` (
                                     `id` varchar(32) NOT NULL,
                                     `device_id` varchar(32) NOT NULL,
                                     `device_code` varchar(64) NOT NULL,
                                     `temperature` decimal(6,2) DEFAULT NULL,
                                     `humidity` decimal(6,2) DEFAULT NULL,
                                     `battery_level` decimal(5,2) DEFAULT NULL,
                                     `signal_strength` int DEFAULT NULL,
                                     `longitude` decimal(10,7) DEFAULT NULL,
                                     `latitude` decimal(10,7) DEFAULT NULL,
                                     `run_status` tinyint DEFAULT '1' COMMENT '1-正常 2-告警 3-故障',
                                     `raw_data` mediumtext COMMENT '原始上报 JSON',
                                     `report_time` datetime NOT NULL,
                                     `receive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     PRIMARY KEY (`id`),
                                     KEY `idx_device_id` (`device_id`),
                                     KEY `idx_device_code` (`device_code`),
                                     KEY `idx_receive_time` (`receive_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备状态上报历史';


-- `realman-boot`.iot_device_status_history 定义

CREATE TABLE `iot_device_status_history` (
                                             `id` varchar(32) NOT NULL COMMENT '与主表一致的流水ID',
                                             `device_id` varchar(32) NOT NULL COMMENT '设备ID',
                                             `device_code` varchar(64) NOT NULL COMMENT '设备编号',
                                             `temperature` decimal(6,2) DEFAULT NULL COMMENT '温度',
                                             `humidity` decimal(6,2) DEFAULT NULL COMMENT '湿度',
                                             `battery_level` decimal(5,2) DEFAULT NULL COMMENT '电量百分比',
                                             `signal_strength` int DEFAULT NULL COMMENT '信号强度',
                                             `longitude` decimal(10,7) DEFAULT NULL COMMENT '经度',
                                             `latitude` decimal(10,7) DEFAULT NULL COMMENT '纬度',
                                             `run_status` tinyint DEFAULT '1' COMMENT '1-正常 2-告警 3-故障',
                                             `raw_data` mediumtext COMMENT '原始上报数据（大 JSON 用 MEDIUMTEXT）',
                                             `report_time` datetime NOT NULL COMMENT '设备上报时间',
                                             `receive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '平台接收时间',
                                             `backup_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
                                             PRIMARY KEY (`id`),
                                             KEY `idx_hist_device_id` (`device_id`),
                                             KEY `idx_hist_device_code` (`device_code`),
                                             KEY `idx_hist_report_time` (`report_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备状态上报历史归档表';


-- `realman-boot`.iot_mq_message_log 定义

CREATE TABLE `iot_mq_message_log` (
                                      `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '主键',
                                      `direction` tinyint NOT NULL COMMENT '消息方向：1=发送，2=接收',
                                      `topic` varchar(128) NOT NULL COMMENT 'MQ Topic',
                                      `tag` varchar(128) DEFAULT NULL COMMENT 'MQ Tag',
                                      `consumer_group` varchar(128) DEFAULT NULL COMMENT '消费者组（发送时为 NULL）',
                                      `message_id` varchar(128) DEFAULT NULL COMMENT 'MQ 消息 ID',
                                      `message_body` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '消息体 JSON（超 4000 字符自动截断）',
                                      `caller_class` varchar(256) DEFAULT NULL COMMENT '发送方/消费方简类名',
                                      `status` tinyint NOT NULL DEFAULT '1' COMMENT '结果状态：1=成功，2=失败',
                                      `fail_reason` text COMMENT '失败原因（超 500 字符截断）',
                                      `cost_time` bigint DEFAULT NULL COMMENT '耗时（毫秒）',
                                      `trace_id` varchar(64) DEFAULT NULL COMMENT '链路追踪 ID',
                                      `create_time` datetime NOT NULL COMMENT '记录时间',
                                      PRIMARY KEY (`id`),
                                      KEY `idx_topic` (`topic`),
                                      KEY `idx_create_time` (`create_time`),
                                      KEY `idx_trace_id` (`trace_id`),
                                      KEY `idx_message_id` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MQ 消息收发日志';


-- `realman-boot`.iot_robot_slam_binding 定义

CREATE TABLE `iot_robot_slam_binding` (
                                          `id` varchar(32) NOT NULL COMMENT '绑定ID',
                                          `tenant_id` varchar(36) DEFAULT NULL COMMENT '租户ID',
                                          `enterprise_id` varchar(36) DEFAULT NULL COMMENT '企业/部门ID',
                                          `robot_id` varchar(36) NOT NULL COMMENT '机器人ID',
                                          `robot_code` varchar(64) NOT NULL COMMENT '机器人编码',
                                          `slam_map_id` varchar(32) NOT NULL COMMENT 'SLAM地图ID',
                                          `state` tinyint NOT NULL DEFAULT '0' COMMENT '0-PENDING 1-ACTIVE 2-OBSOLETE 3-FAILED',
                                          `pending_task_id` varchar(32) DEFAULT NULL COMMENT '同步任务ID',
                                          `effective_time` datetime DEFAULT NULL COMMENT '生效时间',
                                          `obsolete_time` datetime DEFAULT NULL COMMENT '作废时间',
                                          `fail_reason` varchar(512) DEFAULT NULL COMMENT '失败原因',
                                          `create_by` varchar(64) DEFAULT NULL,
                                          `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                          `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                                          `del_flag` tinyint NOT NULL DEFAULT '0',
                                          PRIMARY KEY (`id`),
                                          KEY `idx_robot_state` (`robot_id`,`state`),
                                          KEY `idx_slam_map` (`slam_map_id`),
                                          KEY `idx_pending_task` (`pending_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机器人SLAM绑定关系';


-- `realman-boot`.iot_slam_command_record 定义

CREATE TABLE `iot_slam_command_record` (
                                           `id` varchar(32) NOT NULL COMMENT '主键（雪花ID）',
                                           `master_code` varchar(64) NOT NULL COMMENT '主控设备编码',
                                           `robot_code` varchar(64) NOT NULL COMMENT '机器人设备编码',
                                           `command_id` varchar(64) NOT NULL COMMENT '请求唯一标识（下发到设备的 commandId）',
                                           `function_name` varchar(64) NOT NULL COMMENT '功能代码（SwitchMode/GetCurrentMap/SaveMap/SinglePointNavigation/MultiWaypointNavigation/SetInitialPose）',
                                           `params_json` mediumtext COMMENT '请求参数 JSON',
                                           `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING（已发送等待响应）/ PARTIAL（部分响应）/ COMPLETED（成功完成）/ FAILED（失败）',
                                           `ack_success` tinyint(1) DEFAULT NULL COMMENT 'ack 响应 success 字段',
                                           `ack_code` int DEFAULT NULL COMMENT 'ack 响应 code 字段（0=成功）',
                                           `ack_message` varchar(512) DEFAULT NULL COMMENT 'ack 响应 message 字段',
                                           `ack_sequence` int DEFAULT NULL COMMENT '当前已收到的最大响应序号',
                                           `ack_total` int DEFAULT NULL COMMENT '本次请求预期总响应次数',
                                           `ack_data_json` mediumtext COMMENT '最终 ack 的 data JSON（最后一次响应的 data 字段）',
                                           `send_time` datetime NOT NULL COMMENT '指令发送时间',
                                           `complete_time` datetime DEFAULT NULL COMMENT '完成时间（收到最终响应或失败响应）',
                                           `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                           `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                                           PRIMARY KEY (`id`),
                                           UNIQUE KEY `uk_command_id` (`command_id`),
                                           KEY `idx_device_code_send_time` (`master_code`,`robot_code`,`send_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLAM 指令请求/响应记录';


-- `realman-boot`.iot_slam_map 定义

CREATE TABLE `iot_slam_map` (
                                `id` varchar(32) NOT NULL COMMENT '主键（雪花ID）',
                                `robot_code` varchar(64) NOT NULL COMMENT '机器人设备编码',
                                `master_code` varchar(64) NOT NULL COMMENT '主控设备编码',
                                `map_name` varchar(128) DEFAULT NULL COMMENT '地图名称（来自设备上报）',
                                `map_version` varchar(64) DEFAULT NULL COMMENT '地图版本号',
                                `minio_path` varchar(512) DEFAULT NULL COMMENT 'MinIO 对象 Key（slam-maps/{robotCode}/{commandId}/{filename}）',
                                `filename` varchar(256) DEFAULT NULL COMMENT '文件名',
                                `mime_type` varchar(64) DEFAULT NULL COMMENT 'MIME 类型（image/png 或 application/octet-stream）',
                                `file_size` int DEFAULT NULL COMMENT '文件大小（字节）',
                                `yaml_content` text COMMENT '地图元数据 YAML 内容',
                                `resolution` double DEFAULT NULL COMMENT '地图分辨率（米/像素）',
                                `width` int DEFAULT NULL COMMENT '地图宽度（像素）',
                                `height` int DEFAULT NULL COMMENT '地图高度（像素）',
                                `command_id` varchar(64) DEFAULT NULL COMMENT '关联的 SLAM 指令 ID',
                                `presigned_url` varchar(2048) DEFAULT NULL COMMENT 'MinIO 预签名 GET URL',
                                `presigned_url_expire_time` datetime DEFAULT NULL COMMENT '预签名 URL 过期时间',
                                `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除：0=有效，1=已被新地图替代',
                                `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                                PRIMARY KEY (`id`),
                                KEY `idx_robot_code_del_flag` (`robot_code`,`del_flag`),
                                KEY `idx_command_id` (`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLAM 地图文件记录';


-- `realman-boot`.iot_slam_sync_task 定义

CREATE TABLE `iot_slam_sync_task` (
                                      `id` varchar(32) NOT NULL COMMENT '任务ID',
                                      `tenant_id` varchar(36) DEFAULT NULL COMMENT '租户ID',
                                      `enterprise_id` varchar(36) DEFAULT NULL COMMENT '企业/部门ID',
                                      `source_robot_id` varchar(36) NOT NULL COMMENT '来源机器人ID',
                                      `source_robot_code` varchar(64) NOT NULL COMMENT '来源机器人编码',
                                      `slam_map_id` varchar(32) NOT NULL COMMENT 'SLAM地图ID',
                                      `target_robot_ids` text COMMENT '目标机器人ID列表(JSON)',
                                      `total_count` int NOT NULL DEFAULT '0' COMMENT '目标总数',
                                      `success_count` int NOT NULL DEFAULT '0' COMMENT '成功数',
                                      `fail_count` int NOT NULL DEFAULT '0' COMMENT '失败数',
                                      `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-RUNNING 1-SUCCESS 2-PARTIAL_FAIL 3-FAIL',
                                      `create_by` varchar(64) DEFAULT NULL COMMENT '创建人',
                                      `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                                      PRIMARY KEY (`id`),
                                      KEY `idx_source_robot` (`source_robot_id`),
                                      KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLAM同步任务';

-- `realman-boot`.work_order 定义

CREATE TABLE `work_order` (
                              `id` varchar(36) NOT NULL COMMENT '工单ID',
                              `task_name` varchar(200) NOT NULL COMMENT '工单任务名称',
                              `agent_id` varchar(36) NOT NULL COMMENT '代理商ID',
                              `agent_name` varchar(100) DEFAULT NULL COMMENT '代理商名称',
                              `department_id` varchar(36) DEFAULT NULL COMMENT '所属部门ID',
                              `department_name` varchar(100) DEFAULT NULL COMMENT '所属部门名称',
                              `compliance_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '绑定合规配置ID',
                              `currency` varchar(10) DEFAULT NULL COMMENT '币种（如CNY/USD）',
                              `unit_price` decimal(14,2) DEFAULT NULL COMMENT '单价',
                              `total_price` decimal(14,2) DEFAULT NULL COMMENT '总价',
                              `remark` varchar(500) DEFAULT NULL COMMENT '备注',
                              `plan_start_time` datetime NOT NULL COMMENT '计划开始时间',
                              `plan_end_time` datetime NOT NULL COMMENT '计划结束时间（失效时间）',
                              `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/STARTED/SUBMITTED/COMPLETED/TIMEOUT/CLOSED',
                              `audit_result` varchar(20) DEFAULT NULL COMMENT '审核结果: 0-不合格，1-合格',
                              `operator_id` varchar(36) DEFAULT NULL COMMENT '开启人员ID',
                              `operator_name` varchar(50) DEFAULT NULL COMMENT '开启人员姓名',
                              `operator_phone` varchar(20) DEFAULT NULL COMMENT '开启人员联系方式',
                              `actual_start_time` datetime DEFAULT NULL COMMENT '实际开启时间',
                              `submit_time` datetime DEFAULT NULL COMMENT '提交时间',
                              `timeout_reason` varchar(100) DEFAULT NULL COMMENT '超时原因',
                              `timeout_reason_source` varchar(20) DEFAULT NULL COMMENT '原因来源: USER/SYSTEM',
                              `audit_by` varchar(50) DEFAULT NULL COMMENT '审核人',
                              `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
                              `audit_comment` varchar(200) DEFAULT NULL COMMENT '审核意见',
                              `close_by` varchar(50) DEFAULT NULL COMMENT '关闭人',
                              `close_time` datetime DEFAULT NULL COMMENT '关闭时间',
                              `close_reason` varchar(200) DEFAULT NULL COMMENT '关闭原因',
                              `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
                              `tenant_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户ID',
                              `source` tinyint NOT NULL DEFAULT '1' COMMENT '来源 1=内部创建 2=达尔文平台',
                              `task_desc` varchar(1000) DEFAULT NULL COMMENT '动作链描述，1.xxx，2.xxx',
                              `quota_total` int DEFAULT NULL COMMENT '采集总条数',
                              `level1_scene_name_en` varchar(200) DEFAULT NULL COMMENT 'Darwin一级场景英文名',
                              `level2_scene_name_en` varchar(200) DEFAULT NULL COMMENT 'Darwin二级场景英文名',
                              `collection_item_name_en` varchar(200) DEFAULT NULL COMMENT 'Darwin采集项英文名',
                              `darwin_data_source` text COMMENT 'Darwin 原始消息 JSON（WorkOrderItem 完整体）',
                              `create_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
                              `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                              `update_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '修改人',
                              `update_time` datetime DEFAULT NULL COMMENT '修改时间',
                              PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单主表';


-- `realman-boot`.work_order_attachment 定义

CREATE TABLE `work_order_attachment` (
                                         `id` varchar(36) NOT NULL,
                                         `work_order_id` varchar(36) NOT NULL COMMENT '工单ID',
                                         `file_url` varchar(500) NOT NULL COMMENT '图片URL',
                                         `file_name` varchar(200) DEFAULT NULL COMMENT '图片文件名',
                                         `description` varchar(200) DEFAULT NULL COMMENT '图片说明',
                                         `create_by` varchar(50) DEFAULT NULL COMMENT '上传人',
                                         `create_time` datetime DEFAULT NULL COMMENT '上传时间',
                                         PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单佐证图片';


-- `realman-boot`.work_order_compliance_config 定义

CREATE TABLE `work_order_compliance_config` (
                                                `id` varchar(36) NOT NULL COMMENT '配置ID',
                                                `agent_id` varchar(36) NOT NULL COMMENT '代理商ID',
                                                `agent_name` varchar(100) DEFAULT NULL COMMENT '代理商名称',
                                                `enterprise_id` varchar(36) DEFAULT NULL COMMENT '企业ID',
                                                `enterprise_name` varchar(100) DEFAULT NULL COMMENT '企业名称',
                                                `task_scene` varchar(100) DEFAULT NULL COMMENT '任务场景',
                                                `timeout_alert_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否自动预警:0-禁用 1-启用',
                                                `timeout_alert_offset` varchar(8) DEFAULT NULL COMMENT '自动预警配置时间（距任务结束前X，H:M:S）',
                                                `task_limit_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否有任务时限:0-否 1-是（默认启动）',
                                                `acceptance_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '工单是否需要验收:0-禁用 1-启用',
                                                `overtime_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用超时提交:0-禁用 1-启用',
                                                `overtime_reason_enum` varchar(20) DEFAULT NULL COMMENT '超时提交原因枚举（用户原因/节假日/设备故障）',
                                                `overtime_reason_desc` varchar(500) DEFAULT NULL COMMENT '超时提交描述',
                                                `auto_close_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '超时未提交策略:0-禁用 1-启用',
                                                `auto_close_offset` varchar(8) DEFAULT NULL COMMENT '启用超时未提交策略时设置的超时时长（H:M:S）',
                                                `apply_status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '应用状态:0-未应用 1-已应用（有工单绑定时为已应用）',
                                                `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
                                                `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                                `update_by` varchar(50) DEFAULT NULL COMMENT '修改人',
                                                `update_time` datetime DEFAULT NULL COMMENT '修改时间',
                                                `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-正常 1-删除',
                                                `tenant_id` varchar(36) DEFAULT NULL COMMENT '租户ID',
                                                PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单合规配置';


-- `realman-boot`.work_order_device 定义

CREATE TABLE `work_order_device` (
                                     `id` varchar(36) NOT NULL,
                                     `work_order_id` varchar(36) NOT NULL COMMENT '工单ID',
                                     `device_type` varchar(20) NOT NULL COMMENT '设备类型: CONTROLLER/ROBOT',
                                     `device_id` varchar(36) NOT NULL COMMENT '计划设备ID',
                                     `device_name` varchar(100) DEFAULT NULL COMMENT '计划设备名称',
                                     `device_code` varchar(100) DEFAULT NULL COMMENT '计划设备编号',
                                     `actual_device_id` varchar(36) DEFAULT NULL COMMENT '实际使用设备ID',
                                     `actual_device_name` varchar(100) DEFAULT NULL COMMENT '实际使用设备名称',
                                     `actual_device_code` varchar(100) DEFAULT NULL COMMENT '实际使用设备编号',
                                     `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                     PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单绑定设备';

