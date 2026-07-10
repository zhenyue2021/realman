package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查询请求。对应 {@code POST /internal/device-info/batch-query}。
 *
 * <p>批量升级选型、版本矩阵等场景使用。默认单次 500 条（见设备基座详细设计 2.3），
 * 调用方可通过 {@link #limit} 显式提高（受 SSOT 侧硬上限保护，见实现类），避免调用方
 * 自身有更高批量上限（如 OTA {@code max_batch_devices}）时被 SSOT 默认值静默截断。
 */
@Data
@Schema(description = "设备批量查询请求")
public class DeviceBatchQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "按内部 ID 查询，与 deviceCodes 二选一或组合使用")
    private List<String> deviceIds;

    @Schema(description = "按设备码（SN）查询")
    private List<String> deviceCodes;

    @Schema(description = "按租户过滤")
    private String tenantId;

    @Schema(description = "按设备类型过滤")
    private DeviceType deviceType;

    @Schema(description = "按型号过滤")
    private String deviceModel;

    @Schema(description = "仅返回在线设备")
    private Boolean onlyOnline;

    @Schema(description = "单次返回条数上限，缺省时 SSOT 侧默认 500；显式传入时仍受 SSOT 侧硬上限保护")
    private Integer limit;
}
