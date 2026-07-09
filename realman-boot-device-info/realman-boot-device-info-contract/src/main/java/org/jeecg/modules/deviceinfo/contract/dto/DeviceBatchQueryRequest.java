package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查询请求。对应 {@code POST /internal/device-info/batch-query}。
 *
 * <p>批量升级选型、版本矩阵等场景使用；上限见设备基座详细设计 2.3（单次 500 条），
 * 由实现方在 biz 层校验，本契约不做数量限制的强类型约束。
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
}
