package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyState;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 设备信息基础服务（SSOT）对外返回的读优化投影。
 *
 * <p>字段定义对齐设备基座详细设计 2.1 {@code device_info} 表；本 DTO 是该表的只读镜像，
 * 不包含凭证、审计等属于设备管理业务平台的字段。
 */
@Data
@Schema(description = "设备基础信息（SSOT 只读投影）")
public class DeviceInfoDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "内部唯一标识（UUID），注册时生成，终身不变")
    private String deviceId;

    @Schema(description = "所属租户，创建后不可变")
    private String tenantId;

    @Schema(description = "设备序列号 / 通信层标识（MQTT clientId），产线生成，全局唯一")
    private String deviceCode;

    @Schema(description = "设备类型")
    private DeviceType deviceType;

    @Schema(description = "型号，如 RealBot-S2 / GLN-RX75 / ECO63-标准版")
    private String deviceModel;

    @Schema(description = "展示名称")
    private String deviceName;

    @Schema(description = "网络硬件地址")
    private String macAddress;

    @Schema(description = "最近一次上报的 IP（心跳同步）")
    private String ipAddress;

    @Schema(description = "固件版本（master/slave 单一版本号，统一大写 V 格式）")
    private String firmwareVersion;

    @Schema(description = "多组件版本（Smart Arm 专用：app/model/fw），对齐 OTA PRD 4.1.3")
    private Map<String, String> firmwareComponents;

    @Schema(description = "在线状态")
    private OnlineStatus onlineStatus;

    @Schema(description = "四态")
    private OccupancyState occupancyState;

    @Schema(description = "OCCUPIED 态细分")
    private OccupancyDetail occupancyDetail;

    @Schema(description = "全生命周期阶段")
    private LifecycleStage lifecycleStage;

    @Schema(description = "测试设备标记，由设备管理业务平台写入")
    private Boolean testDevice;

    @Schema(description = "位置信息（国家/城市/区/街道/楼宇 + 经纬度），JSON 结构不做强类型建模")
    private Map<String, Object> location;

    @Schema(description = "最近心跳时间")
    private LocalDateTime lastHeartbeatAt;

    @Schema(description = "最近上线时间")
    private LocalDateTime lastOnlineAt;

    @Schema(description = "最近下线时间")
    private LocalDateTime lastOfflineAt;

    @Schema(description = "离线原因，如 KEEPALIVE_TIMEOUT")
    private String offlineReason;

    @Schema(description = "主控端 ↔ 机器人绑定关系快照（读优化，权威数据在设备管理业务平台）")
    private List<String> boundDeviceIds;

    @Schema(description = "部件级 SN（臂/底盘/主控），对应台账部件级扩展预留字段")
    private Map<String, String> componentSnMap;

    @Schema(description = "乐观锁 / 变更版本号，供下游做增量同步判断")
    private Long dataVersion;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
