package org.jeecg.modules.deviceinfo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备信息基础服务（SSOT）核心表，读优化投影。字段定义对齐设备基座详细设计 2.1。
 *
 * <p>JSON 结构字段（{@code firmwareComponents}/{@code location}/{@code boundDeviceIds}/
 * {@code componentSnMap}）以原始 JSON 文本存储（{@code varchar}/{@code text}），序列化/
 * 反序列化在 service 层用 Jackson 完成，不引入额外的 MyBatis-Plus TypeHandler 依赖。
 */
@Data
@TableName("device_info")
public class DeviceInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 内部唯一标识（UUID），注册时生成，终身不变。 */
    @TableId(value = "device_id", type = IdType.INPUT)
    private String deviceId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("device_code")
    private String deviceCode;

    /** {@link org.jeecg.modules.deviceinfo.contract.enums.DeviceType} 的字符串形式。 */
    @TableField("device_type")
    private String deviceType;

    @TableField("device_model")
    private String deviceModel;

    @TableField("device_name")
    private String deviceName;

    @TableField("mac_address")
    private String macAddress;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("firmware_version")
    private String firmwareVersion;

    /** JSON 文本：{"app":"V1.0.0","model":"V1.0.0","fw":"V1.0.0"}，Smart Arm 专用。 */
    @TableField("firmware_components")
    private String firmwareComponents;

    /** {@link org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus} 的字符串形式。 */
    @TableField("online_status")
    private String onlineStatus;

    /** {@link org.jeecg.modules.deviceinfo.contract.enums.OccupancyState} 的字符串形式。 */
    @TableField("occupancy_state")
    private String occupancyState;

    /** {@link org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail} 的字符串形式。 */
    @TableField("occupancy_detail")
    private String occupancyDetail;

    /** {@link org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage} 的字符串形式。 */
    @TableField("lifecycle_stage")
    private String lifecycleStage;

    @TableField("is_test_device")
    private Boolean testDevice;

    /** JSON 文本：国家/城市/区/街道/楼宇 + 经纬度。 */
    @TableField("location")
    private String location;

    /** JSON 文本：最近一次心跳携带的资源快照（磁盘/内存/电源/网络等），供 OTA 前置资源校验使用。 */
    @TableField("resource_snapshot")
    private String resourceSnapshot;

    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @TableField("last_online_at")
    private LocalDateTime lastOnlineAt;

    @TableField("last_offline_at")
    private LocalDateTime lastOfflineAt;

    @TableField("offline_reason")
    private String offlineReason;

    /** JSON 数组文本：主控端 ↔ 机器人绑定关系快照。 */
    @TableField("bound_device_ids")
    private String boundDeviceIds;

    /** JSON 文本：部件级 SN（臂/底盘/主控）。 */
    @TableField("component_sn_map")
    private String componentSnMap;

    /** 乐观锁 / 变更版本号，供下游做增量同步判断；同时作为 MyBatis-Plus 乐观锁字段。 */
    @Version
    @TableField("data_version")
    private Long dataVersion;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
