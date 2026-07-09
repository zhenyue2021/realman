package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 绑定关系快照同步。对应 {@code PUT /internal/device-info/{deviceId}/binding}，
 * 由设备管理业务平台在主控端 ↔ 机器人绑定变更后调用。权威数据在设备管理业务平台，
 * 本请求只是把最新快照同步进 SSOT 的读优化投影。
 */
@Data
@Schema(description = "绑定关系快照同步请求")
public class BindingUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "当前绑定的对端设备 ID 列表（V1 一对一时长度为 1，V2 多对多可为多个）")
    private List<String> boundDeviceIds;
}
