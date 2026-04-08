package org.jeecg.modules.device.constant;

/**
 * IoT 设备管理模块常量定义
 *
 * <p>模块整体架构：
 * <pre>
 *   设备端 → EMQX（MQTT Broker）→ MqttConfig 订阅 → MqttMessageDispatcher 分发 → 各 Handler 处理
 *   平台端 → DeviceController/OtaController → Service → MqttPublisher → EMQX → 设备端
 * </pre>
 *
 * <p>鉴权模型：
 *   设备使用 deviceCode 作为 clientId/username，MD5(deviceCode) 作为 password
 *   EMQX HTTP Auth 插件回调本平台 /internal/mqtt/auth 完成连接层鉴权
 *   连接建立后，消息 Payload 使用 Per-Device AES-256-CBC 加密
 */
public interface DeviceConstant {

    /**
     * 设备状态常量
     * <p>状态流转：INACTIVE → ONLINE ↔ OFFLINE，DISABLED 为强制禁用
     */
    interface DeviceStatus {
        /** 未激活（新设备默认状态，首次上线后变为 ONLINE） */
        int INACTIVE  = 0;
        /** 在线（已建立 MQTT 连接） */
        int ONLINE    = 1;
        /** 离线（MQTT 连接断开 或 超时无心跳） */
        int OFFLINE   = 2;
        /** 已禁用（禁用后立即清除密钥缓存，EMQX 将拒绝该设备连接） */
        int DISABLED  = 3;
        /** 使用中（遥操中） */
        int IN_USE    = 4;
    }

    /**
     * 设备使用状态常量
     */
    interface UseStatus {
        /** 空闲（未被任何遥操任务占用） */
        int IDLE   = 0;
        /** 占用（遥操中） */
        int IN_USE = 1;
    }

    /**
     * MQTT Topic 模板（均为 printf 格式，%s 填入 deviceCode）
     *
     * <p>Topic 命名规则：
     * <pre>
     *   上行（设备 → 平台）：device/{deviceCode}/status/report  等
     *   下行（平台 → 设备）：device/{deviceCode}/config/push    等
     *   系统事件（EMQX）  ：$SYS/brokers/+/clients/+/connected 等
     * </pre>
     */
    interface MqttTopic {
        /** 上行：设备状态上报（温湿度/电量/信号/定位等） */
        String STATUS_REPORT    = "device/%s/status/report";
        /** 上行：设备配置同步结果确认（响应平台下发的 CONFIG_PUSH） */
        String CONFIG_ACK       = "device/%s/config/ack";
        /** 上行：指令集确认（统一为 device/{code}/command/{cmd}/ack，由平台订阅 device/+/command/+/ack） */
        String COMMAND_ACK      = "device/%s/command/%s/ack";
        /** 下行：平台向设备发送远程重启指令（加密，QoS=1） */
        String REMOTE_RESTART   = "device/%s/command/restart";
        /** 下行：平台向设备发送紧急停机指令（加密，QoS=1） */
        String EMERGENCY_STOP   = "device/%s/command/emergency-stop";
        /** 下行：平台向设备发送停止遥操指令（加密，QoS=1） */
        String DEVICE_STOP_CONTROL   = "device/%s/command/stop-control";
        /** 上行：指令集确认（统一为 master/{code}/command/{cmd}/ack，由平台订阅 master/+/command/+/ack） */
        String MASTER_COMMAND_ACK = "master/%s/command/%s/ack";
        /** 下行：平台向机器人设备发送设置力反馈指令（加密，QoS=1） */
        String MASTER_FORCE_FEEDBACK = "master/%s/command/force-feedback";
        /** 下行：平台向主控设备发送运动与安全参数指令（加密，QoS=1），如底盘/升降速度 */
        String MASTER_SPORT_SPEED = "master/%s/command/sport-speed";
        /** 下行：平台向主控设备发送停止遥操指令（加密，QoS=1） */
        String MASTER_STOP_CONTROL = "master/%s/command/stop-control";
        /** 上行：OTA 升级进度上报（含下载百分比、已下载字节、成功/失败状态） */
        String OTA_PROGRESS     = "device/%s/ota/progress";
        /** 上行：设备操作日志上报（设备端主动记录的行为日志） */
        String OPERATION_LOG    = "device/%s/log/operation";
        /** 下行：平台向设备推送参数配置（加密，QoS=1） */
        String CONFIG_PUSH      = "device/%s/config/push";
        /** 下行：平台向设备推送 OTA 升级通知（含固件下载 URL、MD5、大小等） */
        String OTA_NOTIFY       = "device/%s/ota/notify";
        /**
         * 下行：平台向机器人查询摄像头视频流地址（Topic: device/{deviceCode}/camera/stream/query）
         *
         * <p>配合 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.CameraStreamQuery} 使用：
         * cameraIndex = null 表示查询全部摄像头，非 null 表示查询指定路数。
         */
        String CAMERA_STREAM_QUERY    = "device/%s/camera/stream/query";
        /**
         * 上行：机器人上报摄像头视频流地址列表（Topic: device/{deviceCode}/camera/stream/ack）
         *
         * <p>配合 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.CameraStreamResponse} 使用，
         * 由 {@link org.jeecg.modules.device.mqtt.handler.DeviceCameraStreamResponseHandler} 处理。
         */
        String CAMERA_STREAM_RESPONSE = "device/%s/camera/stream/ack";

        /**
         * 下行：平台向主控查询“当前关联的机器人/设备信息”
         *
         * <p>Topic: master/{controllerCode}/teleop/associated-device/query
         *
         * <p>配合 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.AssociatedDeviceQuery} 使用，
         * 主控收到后应回复 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.AssociatedDeviceResponse}（见 {@link #ASSOCIATED_DEVICE_ACK}）。
         *
         * <p>联调说明见 {@code docs/mqtt-associated-device-debug.md}。
         */
        String ASSOCIATED_DEVICE_QUERY    = "master/%s/teleop/associated-device/query";
        /**
         * 上行：主控上报“当前关联的机器人/设备信息”
         *
         * <p>Topic: master/{controllerCode}/teleop/associated-device/ack（与平台 {@code MqttConfig} 订阅一致）
         */
        String ASSOCIATED_DEVICE_ACK = "master/%s/teleop/associated-device/ack";

        /**
         * 下行：平台通知主控当前应操作的机器人（Topic: device/{controllerCode}/teleop/robot/assign）
         *
         * <p>配合 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.RobotAssignCommand} 使用，
         * 主控收到后即知晓本次遥操任务对应的目标机器人。
         */
        String TELEOP_ROBOT_ASSIGN = "master/%s/teleop/robot/assign";
        /** 上行：机器人请求 SLAM 上传许可 */
        String SLAM_UPLOAD_REQUEST = "device/%s/slam/upload/request";
        /** 下行：平台下发 SLAM 上传许可（包含预签名 PUT URL） */
        String SLAM_UPLOAD_PERMIT = "device/%s/slam/upload/permit";
        /** 上行：机器人通知 SLAM 上传完成 */
        String SLAM_UPLOAD_COMPLETE = "device/%s/slam/upload/complete";
        /** 下行：平台下发 SLAM 同步指令（包含预签名 GET URL） */
        String SLAM_SYNC_COMMAND = "device/%s/slam/sync/command";
        /** 上行：机器人回传 SLAM 同步结果 */
        String SLAM_SYNC_ACK = "device/%s/slam/sync/ack";
        /**
         * 上行：设备请求外部系统服务参数（如 STS 临时凭证）
         *
         * <p>Topic: device/{deviceCode}/ext-params/request
         * <p>配合 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.ExtParamsRequest} 使用，
         * 平台收到后从 Redis 缓存读取并通过 {@link #EXT_PARAMS_RESPONSE} 下发。
         */
        String EXT_PARAMS_REQUEST  = "device/%s/ext-params/request";
        /**
         * 下行：平台下发外部系统服务参数
         *
         * <p>Topic: device/{deviceCode}/ext-params/ack
         * <p>配合 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.ExtParamsResponse} 使用。
         */
        String EXT_PARAMS_RESPONSE = "device/%s/ext-params/ack";

        /** 下行：平台向设备发送建图/定位/导航指令 */
        String SLAM_REQUEST  = "device/%s/slam/request";
        /** 上行：设备响应建图/定位/导航指令（含 sequence/total 多次响应） */
        String SLAM_ACK      = "device/%s/slam/ack";
        /** 上行：设备上报 SLAM 地图模式及当前位姿 */
        String SLAM_STATES   = "device/%s/slam/states";

        /** EMQX 系统事件：设备 MQTT 连接建立（clientId 从 topic 路径中提取） */
        String SYS_CONNECTED    = "$SYS/brokers/+/clients/+/connected";
        /** EMQX 系统事件：设备 MQTT 连接断开 */
        String SYS_DISCONNECTED = "$SYS/brokers/+/clients/+/disconnected";
    }

    /**
     * OTA 升级记录状态流转：
     * <pre>
     *   PENDING → NOTIFIED → CONFIRMED → DOWNLOADING → DOWNLOADED → INSTALLING → SUCCESS
     *                                                                          ↘ FAILED
     *   任何阶段超时 → TIMEOUT（由定时任务 checkOtaTimeout 检测）
     * </pre>
     */
    interface OtaUpgradeStatus {
        /** 待通知（任务已创建，尚未下发 OTA 通知消息） */
        int PENDING     = 0;
        /** 已通知（平台已发送 OTA_NOTIFY，等待设备确认） */
        int NOTIFIED    = 1;
        /** 设备已确认收到通知，准备开始下载 */
        int CONFIRMED   = 2;
        /** 下载中（设备正在分片下载固件） */
        int DOWNLOADING = 3;
        /** 下载完成（校验 MD5 通过，准备安装） */
        int DOWNLOADED  = 4;
        /** 安装中（固件写入 Flash） */
        int INSTALLING  = 5;
        /** 升级成功（设备重启后上报新版本号） */
        int SUCCESS     = 6;
        /** 升级失败（下载/校验/安装任一环节出错） */
        int FAILED      = 7;
        /** 升级超时（超过 Timeout.OTA_UPGRADE_TIMEOUT_MINUTES 分钟无进度更新） */
        int TIMEOUT     = 8;
    }

    /**
     * 配置同步状态
     * <pre>
     *   PENDING → SUCCESS（设备回复 ConfigAck code=0）
     *   PENDING → FAILED （设备回复 ConfigAck code≠0 或同步超时）
     * </pre>
     */
    interface ConfigSyncStatus {
        /** 待同步（已保存至 DB，尚未得到设备确认） */
        int PENDING = 0;
        /** 同步成功 */
        int SUCCESS = 1;
        /** 同步失败 */
        int FAILED  = 2;
    }

    /**
     * 设备操作类型（用于操作日志分类）
     */
    interface OperationType {
        /** 参数修改（平台下发配置 或 设备配置同步） */
        String PARAM_MODIFY      = "PARAM_MODIFY";
        /** 固件升级（OTA 流程） */
        String FIRMWARE_UPGRADE  = "FIRMWARE_UPGRADE";
        /** 远程重启指令 */
        String REMOTE_RESTART    = "REMOTE_RESTART";
        /** 紧急停机指令 */
        String EMERGENCY_STOP    = "EMERGENCY_STOP";
        /** 关机指令 */
        String POWER_OFF         = "POWER_OFF";
        /** 复位指令 */
        String RESET             = "RESET";
        /** 设备上线事件 */
        String DEVICE_ONLINE     = "DEVICE_ONLINE";
        /** 设备离线事件 */
        String DEVICE_OFFLINE    = "DEVICE_OFFLINE";
        /** 设备注册（新设备首次添加） */
        String DEVICE_REGISTER   = "DEVICE_REGISTER";
        /** 向设备发送指令 */
        String COMMAND_SEND      = "COMMAND_SEND";
        /** 设备密钥重置 */
        String TOKEN_REFRESH     = "SECRET_RESET";
    }

    /**
     * 操作来源：区分由平台发起还是设备端主动上报
     */
    interface OperationSource {
        /** 由管理平台发起的操作（Controller → Service） */
        String PLATFORM = "PLATFORM";
        /** 由设备端主动上报的事件（MQTT 消息） */
        String DEVICE   = "DEVICE";
    }
    /**
     * 设备类型
     */
    interface DeviceType {
        /**
         * 主控设备
         */
        String CONTROLLER = "2";
        /**
         * 机器人设备
         */
        String ROBOT = "1";
    }
    /**
     * 设备类型
     */
    interface DEVICE_TYPE_INTEGER {
        /**
         * 主控设备
         */
        int CONTROLLER = 2;
        /**
         * 机器人设备
         */
        int ROBOT = 1;
    }

    /**
     * Redis Key 前缀规范（统一命名空间 iot:）
     */
    interface RedisKey {
        /** 设备实时状态缓存 Key：iot:device:status:{deviceCode}，TTL = 离线阈值 + 1min */
        String DEVICE_STATUS_PREFIX = "iot:device:status:";
        /** 设备 MQTT 密钥缓存 Key：iot:device:secret:{deviceCode}，TTL = 24h */
        String DEVICE_SECRET_PREFIX = "iot:device:secret:";
        /** 在线设备集合（Redis Set）：iot:device:online，成员为 deviceCode */
        String DEVICE_ONLINE_SET    = "iot:device:online";
        /** OTA 断点续传进度 Key：iot:ota:progress:{deviceCode}:{recordId}，值为已下载字节数 */
        String OTA_PROGRESS_PREFIX  = "iot:ota:progress:";
        /** 配置同步等待 Key：iot:config:sync:{deviceCode}:{commandId}，TTL = CONFIG_SYNC_TIMEOUT_SECONDS */
        String CONFIG_SYNC_PREFIX   = "iot:config:sync:";
        /** 固件分片上传进度 Key：iot:upload:chunk:{uploadId}，值为 Set<chunkIndex> */
        String UPLOAD_CHUNK_PREFIX  = "iot:upload:chunk:";
        /** SLAM 上传会话缓存：iot:slam:upload:session:{deviceCode}:{requestId} */
        String SLAM_UPLOAD_SESSION_PREFIX = "iot:slam:upload:session:";
        /** 遥操关系缓存（主控→机器人）：iot:teleop:master2robot:{masterCode}，值为 robotCode */
        String TELEOP_MASTER_TO_ROBOT = "iot:teleop:master2robot:";
        /** 遥操关系缓存（机器人→主控）：iot:teleop:robot2master:{robotCode}，值为 masterCode */
        String TELEOP_ROBOT_TO_MASTER = "iot:teleop:robot2master:";
        /** 设备房间缓存（按主控）：iot:room:master:{masterCode}，值为 JSON(DeviceRoomVO)，TTL=24h */
        String ROOM_MASTER_PREFIX = "iot:room:master:";
        /** 设备房间反查索引（按机器人）：iot:room:robot:{robotCode}，值为 masterCode，TTL=24h */
        String ROOM_ROBOT_PREFIX = "iot:room:robot:";
        /** 活跃房间集合：iot:room:active，成员为 masterCode（WAITING/ACTIVE 状态的房间） */
        String ROOM_ACTIVE_SET = "iot:room:active";
        /**
         * 信令服务器房间密钥：iot:signaling:key:{serverUrl}，值为 64 位 Hex 密钥，TTL=26h
         *
         * <p>完整 Key 示例：{@code iot:signaling:key:192.168.1.100}
         */
        String SIGNALING_KEY_PREFIX = "iot:signaling:key:";
    }

    /**
     * 各类超时阈值配置
     */
    interface Timeout {
        /** OTA 升级超时阈值（分钟）：超过此时间无进度更新则标记为 TIMEOUT */
        long OTA_UPGRADE_TIMEOUT_MINUTES      = 30L;
        /** 配置同步等待超时（秒）：超过此时间未收到 ConfigAck 则认为同步失败 */
        long CONFIG_SYNC_TIMEOUT_SECONDS      = 30L;
        /** 设备离线判定阈值（分钟）：状态 Redis Key 的 TTL，Key 消失即视为设备离线 */
        long DEVICE_OFFLINE_THRESHOLD_MINUTES = 5L;
        /** SLAM 上传许可有效期（分钟） */
        long SLAM_UPLOAD_PERMIT_MINUTES = 30L;
    }

    interface SlamMapStatus {
        int UPLOADING = 0;
        int READY = 1;
        int DELETED = 2;
    }

    interface SlamBindingState {
        int PENDING = 0;
        int ACTIVE = 1;
        int OBSOLETE = 2;
        int FAILED = 3;
    }

    interface SlamSyncTaskStatus {
        int RUNNING = 0;
        int SUCCESS = 1;
        int PARTIAL_FAIL = 2;
        int FAIL = 3;
    }

    /**
     * SLAM 指令请求记录状态
     * <pre>
     *   PENDING → PARTIAL（收到中间响应 sequence < total）
     *   PARTIAL → COMPLETED（收到最终响应 sequence == total 且 success=true）
     *   PENDING/PARTIAL → FAILED（success=false 或 code≠0）
     * </pre>
     */
    interface SlamCommandStatus {
        /** 已发送，等待设备响应 */
        String PENDING   = "PENDING";
        /** 收到部分响应（多次响应场景，尚未收到最终响应） */
        String PARTIAL   = "PARTIAL";
        /** 已完成（收到最终响应且成功） */
        String COMPLETED = "COMPLETED";
        /** 失败 */
        String FAILED    = "FAILED";
    }

    /**
     * SLAM 功能代码
     */
    interface SlamFunction {
        String SWITCH_MODE                      = "SwitchMode";
        String GET_CURRENT_MAP                  = "GetCurrentMap";
        String SAVE_MAP                         = "SaveMap";
        String SINGLE_POINT_NAVIGATION          = "SinglePointNavigation";
        String EXECUTE_SINGLE_POINT_NAVIGATION  = "ExecuteSinglePointNavigation";
        String MULTI_WAYPOINT_NAVIGATION        = "MultiWaypointNavigation";
        String SET_INITIAL_POSE                 = "SetInitialPose";
        /** 查询导航当前规划路径（由监控任务周期触发，结果实时推 WebSocket） */
        String GET_CURRENT_PLANNED_PATH         = "GetCurrentPlannedPath";
    }

    /**
     * WebSocket 推送消息类型（对应前端 msg.type 字段）
     */
    interface WebSocketType {
        /** 设备综合状态（温湿度/电量/信号/定位等） */
        String STATUS                = "STATUS";
        /** 机器人原始状态上报 */
        String ROBOT_STATUS          = "ROBOT_STATUS";
        /** 主控设备原始状态上报 */
        String MASTER_STATUS         = "MASTER_STATUS";
        /** 主控设备指令上报 */
        String MASTER_CMD            = "MASTER_CMD";
        /** 设备上下线事件 */
        String ONLINE_STATUS         = "ONLINE_STATUS";
        /** OTA 升级进度 */
        String OTA_PROGRESS          = "OTA_PROGRESS";
        /** 工单待执行提醒（到达 plan_start_time） */
        String WORK_ORDER_PENDING    = "WORK_ORDER_PENDING";
        /** 工单已开始提醒 */
        String WORK_ORDER_STARTED    = "WORK_ORDER_STARTED";
        /** 主控当前关联设备信息 */
        String ASSOCIATED_DEVICE_INFO = "ASSOCIATED_DEVICE_INFO";
    }

    /**
     * Redis Key - SLAM 相关
     */
    interface SlamRedisKey {
        /** 设备 SLAM 当前状态快照：iot:slam:states:{deviceCode} */
        String SLAM_STATES_PREFIX = "iot:slam:states:";
    }
}
