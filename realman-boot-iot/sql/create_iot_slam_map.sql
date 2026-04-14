-- SLAM 地图文件记录表
-- 每次 GetCurrentMap 成功后，地图文件异步上传至 MinIO，本表记录 MinIO 路径及预签名 URL
CREATE TABLE IF NOT EXISTS `iot_slam_map` (
    `id`                        VARCHAR(32)     NOT NULL                    COMMENT '主键（雪花ID）',
    `robot_code`                VARCHAR(64)     NOT NULL                    COMMENT '机器人设备编码',
    `master_code`               VARCHAR(64)     NOT NULL                    COMMENT '主控设备编码',
    `map_name`                  VARCHAR(128)                                COMMENT '地图名称（来自设备上报）',
    `map_version`               VARCHAR(64)                                 COMMENT '地图版本号（V{major}.{minor}.{patch}，每次成功上传自动 +1）',
    `minio_path`                VARCHAR(512)                                COMMENT 'MinIO 对象 Key（slam-maps/{robotCode}/{commandId}/{filename}）',
    `filename`                  VARCHAR(256)                                COMMENT '文件名',
    `mime_type`                 VARCHAR(64)                                 COMMENT 'MIME 类型（image/png 或 application/octet-stream）',
    `file_size`                 INT                                         COMMENT '文件大小（字节）',
    `yaml_content`              TEXT                                        COMMENT '地图元数据 YAML 内容',
    `resolution`                DOUBLE                                      COMMENT '地图分辨率（米/像素）',
    `width`                     INT                                         COMMENT '地图宽度（像素）',
    `height`                    INT                                         COMMENT '地图高度（像素）',
    `command_id`                VARCHAR(64)                                 COMMENT '关联的 SLAM 指令 ID',
    `presigned_url`             VARCHAR(2048)                               COMMENT 'MinIO 预签名 GET URL',
    `presigned_url_expire_time` DATETIME                                    COMMENT '预签名 URL 过期时间',
    `del_flag`                  TINYINT(1)      NOT NULL DEFAULT 0          COMMENT '逻辑删除：0=有效，1=已被新地图替代',
    `create_time`               DATETIME                                    COMMENT '创建时间',
    `update_time`               DATETIME                                    COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_robot_code_deleted` (`robot_code`, `is_deleted`),
    KEY `idx_command_id` (`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLAM 地图文件记录（MinIO 存储）';
