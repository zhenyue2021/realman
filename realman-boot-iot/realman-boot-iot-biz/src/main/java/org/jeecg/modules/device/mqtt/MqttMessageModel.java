package org.jeecg.modules.device.mqtt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * MQTT 消息协议体定义（所有 Payload 均经 Per-Device AES-256-CBC 加密传输）
 *
 * <p>消息流向说明：
 * <pre>
 *   上行（设备 → 平台）：StatusReport / ConfigAck / RestartAck / OtaProgress / OperationLogReport
 *   下行（平台 → 设备）：ConfigPush / RemoteRestartCommand / OtaNotify
 * </pre>
 *
 * <p>加密协议：
 *   密文格式 = ivHex(32char) + ":" + Base64(AES密文)
 *   密钥派生 = SHA256(deviceCode)[0..31]（设备端离线计算，无需存储）
 *
 * @see org.jeecg.modules.device.security.CommandEncryptService 加解密实现
 */
public class MqttMessageModel {

    /**
     * 上行：设备状态上报（Topic: device/{deviceCode}/status/report）
     *
     * <p>设备定期（或状态变化时）上报自身运行状态，平台收到后：
     * <ol>
     *   <li>解密 → 解析本类</li>
     *   <li>更新 Redis 实时状态缓存（TTL=离线阈值+1min）</li>
     *   <li>维护在线集合（iot:device:online）</li>
     *   <li>异步写入 DB 历史状态表（iot_device_status）</li>
     *   <li>WebSocket 推送前端实时刷新</li>
     * </ol>
     *
     * @see org.jeecg.modules.device.mqtt.handler.DeviceStatusHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusReport {
        /** 温度（℃），可为 null */
        private BigDecimal temperature;
        /** 湿度（%RH），可为 null */
        private BigDecimal humidity;
        /** 电量百分比（0-100），可为 null */
        private BigDecimal batteryLevel;
        /** 信号强度（dBm），可为 null */
        private Integer signalStrength;
        /** 运行状态（业务自定义枚举值），可为 null */
        private Integer runStatus;
        /** 经度（WGS84），可为 null */
        private BigDecimal longitude;
        /** 纬度（WGS84），可为 null */
        private BigDecimal latitude;
        /** 消息时间戳（毫秒 epoch，设备本地时间） */
        private long timestamp;
        /** 扩展字段（业务自定义 KV），可为 null */
        private Map<String, Object> extra;
    }

    /**
     * 下行：平台向设备推送参数配置（Topic: device/{deviceCode}/config/push）
     *
     * <p>平台调用 /api/device/{deviceId}/config/sync 时，若设备在线则立即发送本消息。
     * 设备收到后应用参数并回复 {@link ConfigAck}；若设备离线，配置以 PENDING 状态存 DB，
     * 待设备上线后由 {@link org.jeecg.modules.device.service.PendingSyncService} 补推。
     *
     * @see org.jeecg.modules.device.mqtt.handler.DeviceConfigAckHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigPush {
        /** 指令唯一 ID（UUID），用于关联 ConfigAck 响应 */
        private String commandId;
        /** 参数键值对（configKey → configValue） */
        private Map<String, Object> params;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：设备配置同步结果确认（Topic: device/{deviceCode}/config/ack）
     *
     * <p>设备收到 {@link ConfigPush} 并应用配置后，上报本消息。
     * 平台收到后根据 code 更新 DB 中对应配置记录的同步状态。
     *
     * @see org.jeecg.modules.device.mqtt.handler.DeviceConfigAckHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigAck {
        /** 对应 {@link ConfigPush#commandId}，用于追踪哪条配置推送被确认 */
        private String commandId;
        /** 执行结果码：0=成功，非0=失败 */
        private int code;
        /** 失败原因描述（code≠0 时填写） */
        private String message;
        /** 设备确认时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台向设备发送远程重启指令（Topic: device/{deviceCode}/command/restart）
     *
     * <p>平台调用 /api/device/{deviceId}/restart，设备在线时立即下发本消息。
     * 设备收到后应执行重启，并在重启完成或无法重启时回复 {@link RestartAck}。
     *
     * @see org.jeecg.modules.device.mqtt.handler.DeviceCommandAckHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemoteRestartCommand {
        /** 指令唯一 ID（UUID），用于关联 RestartAck 响应 */
        private String commandId;
        /** 重启原因说明（操作人填写，便于日志追溯） */
        private String reason;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：设备重启执行确认（Topic: device/{deviceCode}/command/restart/ack）
     *
     * <p>设备收到 {@link RemoteRestartCommand} 后，若能立即重启则回复 code=0，
     * 若无法重启（如正在 OTA 或关键任务中）则回复 code≠0 并说明原因。
     *
     * @see org.jeecg.modules.device.mqtt.handler.DeviceCommandAckHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestartAck {
        /** 对应 {@link RemoteRestartCommand#commandId} */
        private String commandId;
        /** 失败或备注信息 */
        private String message;
        /** 执行结果码：0=已执行重启，非0=拒绝重启 */
        private int code;
        /** 设备回复时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台向设备发送紧急停机指令（Topic: device/{deviceCode}/command/emergency-stop）
     *
     * <p>设备收到后应立即进入安全停机状态，并回复 {@link EmergencyStopAck}。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmergencyStopCommand {
        /** 指令唯一 ID（UUID），用于关联 EmergencyStopAck 响应 */
        private String commandId;
        /** 停机原因说明（操作人填写，便于日志追溯） */
        private String reason;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：设备紧急停机执行确认（Topic: device/{deviceCode}/command/emergency-stop/ack）
     *
     * <p>设备收到 {@link EmergencyStopCommand} 后，若已进入停机状态则 code=0；
     * 若拒绝或失败则 code≠0 并说明原因。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmergencyStopAck {
        /** 对应 {@link EmergencyStopCommand#commandId} */
        private String commandId;
        /** 失败或备注信息 */
        private String message;
        /** 执行结果码：0=已停机，非0=失败/拒绝 */
        private int code;
        /** 设备回复时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台向机器人设备设置力反馈参数（Topic: master/{controllerCode}/command/force-feedback）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MasterForceFeedbackCommand {
        /** 指令唯一 ID（UUID），用于关联 ACK */
        private String commandId;
        /** 机械臂力度等级 */
        private Integer armLevel;
        /** 夹爪力度等级 */
        private Integer gripperLevel;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台向主控设备设置运动与安全参数（Topic: master/{controllerCode}/command/sport-speed）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MasterSportSpeedCommand {
        /** 指令唯一 ID（UUID），用于关联 ACK */
        private String commandId;
        /** 底盘行进速度等级 */
        private Integer moveSpeedLevel;
        /** 身体升降速度等级 */
        private Integer liftSpeedLevel;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台向设备推送 OTA 升级通知（Topic: device/{deviceCode}/ota/notify）
     *
     * <p>执行升级任务时，平台为每台目标设备发送本消息，携带固件下载地址和校验信息。
     * 设备下载固件时应支持断点续传（通过 downloadedBytes 记录已下载偏移）。
     *
     * @see org.jeecg.modules.device.mqtt.handler.OtaProgressHandler
     * @see org.jeecg.modules.device.service.impl.IotOtaServiceImpl#executeUpgradeTask
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtaNotify {
        /** 升级任务 ID（对应 iot_ota_upgrade_task.id） */
        private String taskId;
        /** 本设备的升级记录 ID（对应 iot_ota_upgrade_record.id） */
        private String recordId;
        /** 固件 ID（对应 iot_ota_firmware.id） */
        private String firmwareId;
        /** 目标固件版本号 */
        private String version;
        /** 固件 HTTP 下载地址（MinIO 预签名 URL，有效期 urlExpireDays 天） */
        private String downloadUrl;
        /** 固件文件 MD5（设备下载完成后校验完整性） */
        private String fileMd5;
        /** 固件文件总大小（字节），用于断点续传计算偏移 */
        private Long fileSize;
        /** 是否强制升级：1=强制（设备不可拒绝），0=可选 */
        private Integer forceUpgrade;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：OTA 升级进度上报（Topic: device/{deviceCode}/ota/progress）
     *
     * <p>设备在 OTA 各阶段（确认/下载中/完成/失败）均需上报本消息。
     * 平台收到后更新升级记录状态、刷新任务统计、通过 WebSocket 推送进度。
     *
     * <p>状态机流转（对应 {@link org.jeecg.modules.device.constant.DeviceConstant.OtaUpgradeStatus}）：
     * NOTIFIED → CONFIRMED → DOWNLOADING → DOWNLOADED → INSTALLING → SUCCESS / FAILED
     *
     * @see org.jeecg.modules.device.mqtt.handler.OtaProgressHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtaProgress {
        /** 对应 {@link OtaNotify#taskId} */
        private String taskId;
        /** 对应 {@link OtaNotify#recordId}，用于精确定位升级记录 */
        private String recordId;
        /** 升级失败原因（status=FAILED 时填写） */
        private String failReason;
        /** 升级成功后的新版本号（status=SUCCESS 时填写，平台用于更新设备固件版本字段） */
        private String newVersion;
        /** 当前升级状态码（见 OtaUpgradeStatus 枚举） */
        private Integer status;
        /** 下载进度（0-100 百分比） */
        private Integer progress;
        /** 已下载字节数（用于断点续传，平台缓存至 Redis） */
        private Long downloadedBytes;
        /** 设备上报时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：设备操作日志上报（Topic: device/{deviceCode}/log/operation）
     *
     * <p>设备端主动记录并上报的行为日志，由平台异步写入 DB。
     * 与平台侧日志（IDeviceOperationLogService）合并展示，形成完整操作审计链路。
     *
     * @see org.jeecg.modules.device.mqtt.handler.DeviceOperationLogHandler
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationLogReport {
        /** 操作类型（参考 DeviceConstant.OperationType 或设备自定义类型） */
        private String operationType;
        /** 操作描述（人读文本） */
        private String operationDesc;
        /** 操作详情（JSON 或附加信息，可为 null） */
        private String operationDetail;
        /** 操作结果：SUCCESS / FAIL */
        private String operationResult;
        /** 设备端操作发生时间戳（毫秒） */
        private long operationTime;
    }

    /**
     * 单路摄像头信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CameraInfo {
        /** 摄像头路数索引（从 0 开始） */
        private Integer cameraIndex;
        /** 摄像头名称/标识，可为 null */
        private String cameraName;
        /** 视频流地址（RTSP/RTMP/HLS 等） */
        private String stream;
        /** 流类型（如 rtsp、rtmp、hls），可为 null */
        private String streamType;
    }

    /**
     * 下行：平台向机器人查询摄像头视频流地址（Topic: device/{deviceCode}/camera/stream/query）
     *
     * <p>cameraIndex 为 null 时查询全部摄像头；否则查询指定路数。
     * 机器人收到后应回复 {@link CameraStreamResponse}。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CameraStreamQuery {
        /** 指令唯一 ID（UUID），用于关联 CameraStreamResponse */
        private String commandId;
        //    host: 172.16.44.66
        //    port: 554
        //    app: live
        //    secret: DvNBLZ961zAIqWrdjgkdcZ9ZJVGJuhhu
        /** 流媒体用到的host，用于关联 CameraStreamResponse */
        private String host;
        /** 流媒体用到的port，用于关联 CameraStreamResponse */
        private String port;
        /** 流媒体用到的app，用于关联 CameraStreamResponse */
        private String app;
        /** 指定摄像头路数索引，null 表示查询全部 */
        private Integer cameraIndex;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：机器人上报摄像头视频流地址列表（Topic: device/{deviceCode}/camera/stream/ack）
     *
     * <p>机器人收到 {@link CameraStreamQuery} 后上报本消息，携带所有（或指定路）摄像头的流地址。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CameraStreamResponse {
        /** 对应 {@link CameraStreamQuery#commandId} */
        private String commandId;
        /** 执行结果码：0=成功，非0=失败 */
        private int code;
        /** 失败原因（code≠0 时填写） */
        private String message;
        /** 摄像头列表 */
        private List<CameraInfo> cameras;
        /** 设备回复时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台向主控查询“当前关联的机器人/设备信息”（Topic: master/{controllerCode}/teleop/associated-device/query）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssociatedDeviceQuery {
        /** 指令唯一 ID（UUID），用于关联 AssociatedDeviceResponse */
        private String commandId;
        /** 操作员ID（可选，用于主控侧日志/校验） */
        private String operatorId;
        /** 登录记录ID（可选，平台用于回写 iot_controller_login_log） */
        private String loginLogId;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 下行：平台通知主控当前应操作的机器人（Topic: master/{controllerCode}/teleop/robot/assign）
     *
     * <p>主控登录后，平台根据工单派发本消息，主控收到后即知晓本次遥操目标机器人。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RobotAssignCommand {
        /** 指令唯一 ID（UUID） */
        private String commandId;
        /** 目标机器人设备编码 */
        private String robotCode;
        /** 关联工单 ID */
        private String workOrderId;
        /** 平台发送时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：主控上报“当前关联的机器人/设备信息”（Topic: master/{controllerCode}/teleop/associated-device/ack）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AssociatedDeviceResponse {
        /** 对应 {@link AssociatedDeviceQuery#commandId} */
        private String commandId;
        /** 执行结果码：0=成功，非0=失败 */
        private int code;
        /** 失败原因（code≠0 时填写） */
        private String message;
        /** 操作员ID（可选，透传） */
        private String operatorId;
        /** 登录记录ID（可选，平台用于回写 iot_controller_login_log） */
        private String loginLogId;

        /** 主控当前识别到的自身 MAC 地址 */
        private String macAddress;

        /** 关联机器人ID（可选） */
        private String robotId;
        /** 关联机器人编码（推荐必填） */
        private String robotCode;

        /** 设备回复时间戳（毫秒） */
        private long timestamp;
    }

    /**
     * 上行：机器人请求 SLAM 上传许可（Topic: device/{robotCode}/slam/upload/request）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamUploadRequest {
        private String requestId;
        private String mapName;
        private String mapVersion;
        private String md5;
        private Long size;
        private String ext;
        private long timestamp;
    }

    /**
     * 下行：平台下发 SLAM 上传许可（Topic: device/{robotCode}/slam/upload/permit）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamUploadPermit {
        private String requestId;
        private String mapId;
        private String objectKey;
        private String putUrl;
        private Long expireAt;
        private long timestamp;
    }

    /**
     * 上行：机器人通知 SLAM 上传完成（Topic: device/{robotCode}/slam/upload/complete）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamUploadComplete {
        private String requestId;
        private String mapId;
        private String objectKey;
        private String md5;
        private Long size;
        private Integer code;
        private String message;
        private long timestamp;
    }

    /**
     * 下行：平台通知机器人同步 SLAM（Topic: device/{robotCode}/slam/sync/command）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamSyncCommand {
        private String taskId;
        private String bindingId;
        private String slamMapId;
        private String sourceRobotCode;
        private String objectKey;
        private String getUrl;
        private String md5;
        private Long size;
        private long timestamp;
    }

    /**
     * 上行：机器人回传 SLAM 同步结果（Topic: device/{robotCode}/slam/sync/ack）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamSyncAck {
        private String taskId;
        private String bindingId;
        private String slamMapId;
        private Integer code;
        private String message;
        private long timestamp;
    }

    /**
     * 上行：设备请求外部系统服务参数（Topic: device/{deviceCode}/ext-params/request）
     *
     * <p>所有设备共享同一套外部参数，由 sourceSystem 标识数据来源。
     * 若 sourceSystem 为空，平台使用配置项 {@code integration.ext-params.default-source-system} 的值。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExtParamsRequest {
        /** 请求唯一ID，响应时原样返回，便于设备侧对账 */
        private String requestId;
        /** 请求方系统编码（如 RM_FUNC_MCAP_UPLOADER） */
        private String sourceSystem;
        /** 目标系统编码（如 GLN_MANAGE_PLATFORM） */
        private String targetSystem;
        /** 业务类型（如 upload_url_request） */
        private String bizType;
        /** 请求时间戳（ISO-8601 本地时间，响应时回传至 params.timestamp） */
        private String timestamp;
        /** 业务参数（dataType / deviceId / fileSize 等，平台透传不解析） */
        private Map<String, Object> params;
    }

    /**
     * 下行：平台响应设备的外部系统服务参数（Topic: device/{deviceCode}/ext-params/ack）
     *
     * <p>code=200 表示成功；code 非 200 表示失败，message 说明原因。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExtParamsResponse {
        /** 与请求的 requestId 对应 */
        private String requestId;
        /** 响应方系统编码（平台侧标识，如 GLN_MANAGE_PLATFORM） */
        private String sourceSystem;
        /** 目标系统编码（即请求方，如 RM_FUNC_MCAP_UPLOADER） */
        private String targetSystem;
        /** 业务类型（如 upload_url_response） */
        private String bizType;
        /** 响应时间戳（ISO-8601 本地时间） */
        private String timestamp;
        /** 200=成功，非 200=失败 */
        private int code;
        /** 失败描述（code != 200 时填充） */
        private String message;
        /** 业务数据（成功时填充） */
        private ResponseParams params;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ResponseParams {
            /** 回传请求方的原始时间戳 */
            private String timestamp;
            /** STS 临时凭证数据 */
            private StsCredential data;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class StsCredential {
            private String endpoint;
            private String bucket;
            private String bjExpiration;
            private String utcExpiration;
            private String accessKeyId;
            private String accessKeySecret;
            private String securityToken;
        }
    }

    /**
     * 下行：平台向设备发送建图/定位/导航指令（Topic: device/{deviceCode}/slam/request）
     *
     * <p>function 枚举值见 {@code DeviceConstant.SlamFunction}。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamRequest {
        /** 请求唯一标识，响应时原样回传 */
        private String commandId;
        /** 功能代码（SwitchMode / GetCurrentMap / SaveMap 等） */
        private String function;
        /** 功能参数（依 function 不同结构不同） */
        private Map<String, Object> params;
    }

    /**
     * 上行：设备响应建图/定位/导航指令（Topic: device/{deviceCode}/slam/ack）
     *
     * <p>单次请求可能有多次响应（sequence/total），最后一次 sequence==total。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamAck {
        private String commandId;
        private String function;
        private Boolean success;
        private Integer code;
        private String message;
        /** 当前响应序号（从 1 开始） */
        private Integer sequence;
        /** 本次请求总响应次数 */
        private Integer total;
        /** 响应数据（依 function 不同结构不同） */
        private Map<String, Object> data;
    }

    /**
     * 上行：设备上报 SLAM 地图状态及当前位姿（Topic: device/{deviceCode}/slam/states）
     *
     * <p>IdleMode 模式下仅有 slamNavMode，其他模式同时包含 currentPose。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlamStates {
        @JsonProperty("slam_nav_mode")
        private String slamNavMode;
        @JsonProperty("current_pose")
        private Pose currentPose;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Pose {
            @JsonProperty("x")
            private Double pixelX;
            @JsonProperty("y")
            private Double pixelY;
            private Double yaw;
        }
    }

    // =========================================================================
    // WebRTC 信令指令（下行：平台 → 机器人）
    // =========================================================================

    /**
     * 下行：WebRTC 统一指令（Topic: webrtc/{deviceCode}/command）
     *
     * <p>{@code command="start"} 时携带信令/TURN/STUN 参数，机器人建立 P2P 连接后回复 {@link WebRtcAck}。
     * <p>{@code command="stop"} 时仅携带 commandId/timestamp，机器人断开连接，平台不等待 ACK（fire-and-forget）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebRtcCommand {
        /** 指令类型：start / stop */
        private String command;
        /** 指令唯一 ID，ACK 回传时带回用于关联等待 Future */
        private String commandId;
        /** 房间号（start 时携带，服务端按主控编码创建/查询） */
        private String roomId;
        /** 信令服务器 URL（start 时携带） */
        private String signalUrl;
        /** 信令服务器访问密钥（start 时携带，每日凌晨 2:00 轮换） */
        private String signalKey;
        /** TURN 服务器列表（start 时携带，stop 时为 null） */
        private List<TurnServer> turnServers;
        /** STUN 服务器地址列表（start 时携带，stop 时为 null） */
        private List<String> stunServers;
        /** 消息时间戳（毫秒 epoch） */
        private long timestamp;

        /** TURN 服务器配置 */
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class TurnServer {
            private String url;
            private String username;
            private String password;
        }
    }

    /**
     * 上行：机器人回复 WebRTC 指令结果（Topic: webrtc/{deviceCode}/command/ack）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebRtcAck {
        /** 回复的指令类型：start / stop */
        private String command;
        /** 对应指令的 commandId */
        private String commandId;
        /** true=成功，false=失败 */
        private boolean success;
        /** 失败原因或补充信息（可为 null） */
        private String message;
        /** 消息时间戳（毫秒 epoch） */
        private long timestamp;
    }

    /**
     * 上行：WebRTC 信令服务启动通知（Topic: device/{deviceCode}/webrtc/restart）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebRtcRestart {
        /** 固定值 "restart" */
        private String command;
        /** 本次启动的唯一 ID */
        private String commandId;
        /** 上报时间戳（毫秒 epoch） */
        private long timestamp;
    }

    /**
     * 上行：机器人设备上线信息上报（Topic: device/{deviceCode}/datacollect/deviceOnline）
     *
     * @see org.jeecg.modules.device.mqtt.model.DeviceOnlineReport 实现类已迁至 model 包
     */
    public static class DeviceOnlineReport extends org.jeecg.modules.device.mqtt.model.DeviceOnlineReport {
        public DeviceOnlineReport() {
            super();
        }

        public DeviceOnlineReport(long timestamp, String deviceSn, OnlinePayload payload) {
            super(timestamp, deviceSn, payload);
        }

        public static class OnlinePayload extends org.jeecg.modules.device.mqtt.model.DeviceOnlineReport.OnlinePayload {
        }

        public static class Location extends org.jeecg.modules.device.mqtt.model.DeviceOnlineReport.Location {
        }
    }
}
