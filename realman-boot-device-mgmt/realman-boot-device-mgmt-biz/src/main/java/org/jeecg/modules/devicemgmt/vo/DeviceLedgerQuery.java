package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;

import java.io.Serializable;

/** 台账列表查询条件，代理转发到 SSOT 的 list 接口后按本层数据补全。 */
@Data
@Schema(description = "设备台账查询条件")
public class DeviceLedgerQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String tenantId;

    private DeviceType deviceType;

    private String deviceModel;

    private OnlineStatus onlineStatus;

    private Boolean testDevice;
}
