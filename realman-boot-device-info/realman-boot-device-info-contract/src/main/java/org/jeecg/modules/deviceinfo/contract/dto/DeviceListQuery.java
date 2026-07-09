package org.jeecg.modules.deviceinfo.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;

import java.io.Serializable;

/**
 * 分页/条件查询请求。对应 {@code GET /internal/device-info/list}，
 * 供设备管理业务平台台账 UI 代理调用。
 */
@Data
@Schema(description = "设备列表分页查询条件")
public class DeviceListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始")
    private Integer pageNo = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 20;

    private String tenantId;

    private DeviceType deviceType;

    private String deviceModel;

    private OnlineStatus onlineStatus;

    private String occupancyState;

    private Boolean testDevice;
}
